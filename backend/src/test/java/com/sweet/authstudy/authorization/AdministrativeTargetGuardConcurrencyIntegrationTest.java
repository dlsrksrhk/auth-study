package com.sweet.authstudy.authorization;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserCommands.UpdateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AdministrativeTargetGuardConcurrencyIntegrationTest {

    @Autowired
    private CompanyService companyService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AccountRepository accountRepository;

    @Test
    void role_grant_commits_before_waiting_company_admin_mutation_is_rechecked() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch grantHasAccountLock = new CountDownLatch(1);
        CountDownLatch releaseGrant = new CountDownLatch(1);
        CountDownLatch mutationReachedAccountLock = new CountDownLatch(1);
        AtomicInteger mutationBackendPid = new AtomicInteger();

        doAnswer(invocation -> {
            Object account = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("system-admin-grant")) {
                grantHasAccountLock.countDown();
                await(releaseGrant, "grant release");
            }
            return account;
        }).when(accountRepository).findByIdForUpdate(anyLong());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("company-admin-mutation")) {
                mutationBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                mutationReachedAccountLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(accountRepository).findByUserIdForUpdate(anyLong());

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> grant = executor.submit(() -> {
                Thread.currentThread().setName("system-admin-grant");
                userService.assignCompanyAdmin(SYSTEM_ADMIN, fixture.company().code(), fixture.user().user().code());
            });
            assertThat(grantHasAccountLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> mutation = executor.submit(() -> {
                Thread.currentThread().setName("company-admin-mutation");
                try {
                    updateName(fixture, "Forbidden change");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThat(mutationReachedAccountLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDatabaseLockWait(mutationBackendPid.get())).isTrue();
            assertThat(mutation.isDone()).isFalse();

            releaseGrant.countDown();
            grant.get(10, TimeUnit.SECONDS);
            Throwable failure = mutation.get(10, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOfSatisfying(ApiException.class,
                    exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally {
            releaseGrant.countDown();
        }

        HrUser stored = userRepository.findById(fixture.user().user().id()).orElseThrow();
        Account account = accountRepository.findByUserId(stored.id()).orElseThrow();
        assertThat(stored.name()).isEqualTo("Original name");
        assertThat(stored.version()).isZero();
        assertThat(account.roles()).contains(AccountRole.COMPANY_ADMIN);
    }

    @Test
    void mutation_linearized_before_role_grant_completes_without_deadlock() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch mutationHasAccountLock = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch grantReachedAccountLock = new CountDownLatch(1);
        AtomicInteger grantBackendPid = new AtomicInteger();

        doAnswer(invocation -> {
            Object account = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("company-admin-mutation")) {
                mutationHasAccountLock.countDown();
                await(releaseMutation, "mutation release");
            }
            return account;
        }).when(accountRepository).findByUserIdForUpdate(anyLong());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("system-admin-grant")) {
                grantBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                grantReachedAccountLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(accountRepository).findByIdForUpdate(anyLong());

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutation = executor.submit(() -> {
                Thread.currentThread().setName("company-admin-mutation");
                updateName(fixture, "Committed first");
            });
            assertThat(mutationHasAccountLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> grant = executor.submit(() -> {
                Thread.currentThread().setName("system-admin-grant");
                userService.assignCompanyAdmin(SYSTEM_ADMIN, fixture.company().code(), fixture.user().user().code());
            });
            assertThat(grantReachedAccountLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDatabaseLockWait(grantBackendPid.get())).isTrue();

            releaseMutation.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            grant.get(10, TimeUnit.SECONDS);
        } finally {
            releaseMutation.countDown();
        }

        HrUser stored = userRepository.findById(fixture.user().user().id()).orElseThrow();
        Account account = accountRepository.findByUserId(stored.id()).orElseThrow();
        assertThat(stored.name()).isEqualTo("Committed first");
        assertThat(account.roles()).contains(AccountRole.COMPANY_ADMIN);
    }

    private void updateName(Fixture fixture, String name) {
        userService.update(fixture.companyAdmin(), fixture.company().code(), fixture.user().user().code(),
                new UpdateUserCommand(name, "010-0000-0000", LocalDate.parse("2026-08-20"),
                        "Seoul", null, "EMPLOYEE", fixture.user().user().version()));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String companyCode = "R" + suffix;
        String domain = companyCode.toLowerCase() + ".example";
        CompanyView company = companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand(companyCode, companyCode, domain));
        CreatedUserView user = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                companyCode, "U001", "E-1001", "Original name", "user@" + domain,
                "010-0000-0000", LocalDate.parse("2026-08-20"), "Seoul", null, "EMPLOYEE"));
        AuthenticatedAccount companyAdmin = new AuthenticatedAccount(
                Long.MAX_VALUE, company.id(), Long.MAX_VALUE,
                Set.of(AccountRole.USER, AccountRole.COMPANY_ADMIN), false);
        return new Fixture(company, user, companyAdmin);
    }

    private boolean awaitDatabaseLockWait(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String waitType = jdbc.queryForObject(
                    "select wait_event_type from pg_stat_activity where pid = ?", String.class, backendPid);
            if ("Lock".equals(waitType)) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private void await(CountDownLatch latch, String description) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for " + description + ".");
        }
    }

    private record Fixture(
            CompanyView company,
            CreatedUserView user,
            AuthenticatedAccount companyAdmin) {
    }
}
