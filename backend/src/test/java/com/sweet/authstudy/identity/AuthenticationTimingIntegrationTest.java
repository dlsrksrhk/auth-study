package com.sweet.authstudy.identity;

import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthenticationTimingIntegrationTest {
    @Autowired AuthenticationService authenticationService;
    @Autowired AccountRepository accountRepository;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @Test
    void missing_account_still_performs_a_bcrypt_comparison() {
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand("missing-" + UUID.randomUUID() + "@example.test", "missing", "127.0.0.1")))
                .isInstanceOf(ApiException.class);

        verify(passwordEncoder).matches(eq("missing"), anyString());
    }

    @Test
    void disabled_account_still_performs_a_dummy_bcrypt_comparison() {
        Account account = systemAccount();
        account.changeStatus(AccountStatus.DISABLED, clock.instant());
        accountRepository.save(account);
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand(account.loginEmail(), "rejected", "127.0.0.1")))
                .isInstanceOf(ApiException.class);

        verify(passwordEncoder).matches(eq("rejected"), anyString());
    }

    @Test
    void locked_account_still_performs_a_dummy_bcrypt_comparison() {
        Account account = systemAccount();
        account.recordFailedLogin(clock.instant().plus(Duration.ofMinutes(15)), clock.instant());
        accountRepository.save(account);
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand(account.loginEmail(), "rejected", "127.0.0.1")))
                .isInstanceOf(ApiException.class);

        verify(passwordEncoder).matches(eq("rejected"), anyString());
    }

    @Test
    void parallel_requests_for_an_existing_email_enter_bcrypt_concurrently() throws Exception {
        Account account = systemAccount();

        assertParallelPasswordComparisons(account.loginEmail(), "parallel-existing-probe");
    }

    @Test
    void parallel_requests_for_a_missing_email_enter_dummy_bcrypt_concurrently() throws Exception {
        String missingEmail = "parallel-missing-" + UUID.randomUUID() + "@example.test";

        assertParallelPasswordComparisons(missingEmail, "parallel-missing-probe");
    }

    @Test
    void password_hash_change_during_bcrypt_discards_the_stale_verification_result() throws Exception {
        Account account = systemAccount();
        String replacementHash = passwordEncoder.encode("ReplacementPassword1234!");
        CountDownLatch bcryptEntered = new CountDownLatch(1);
        CountDownLatch releaseBcrypt = new CountDownLatch(1);
        CountDownLatch passwordUpdated = new CountDownLatch(1);
        doAnswer(invocation -> {
            bcryptEntered.countDown();
            if (!releaseBcrypt.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("BCrypt verification was not released.");
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(eq("SystemPassword1234!"), anyString());

        int loginStatus;
        boolean updateCompletedOutsideBcryptLock;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> {
                return mvc.perform(post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"" + account.loginEmail()
                                        + "\",\"password\":\"SystemPassword1234!\"}"))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(bcryptEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var update = executor.submit(() -> {
                try {
                    Account latest = accountRepository.findById(account.id()).orElseThrow();
                    latest.changePassword(replacementHash, clock.instant());
                    accountRepository.save(latest);
                } finally {
                    passwordUpdated.countDown();
                }
            });
            updateCompletedOutsideBcryptLock = passwordUpdated.await(2, TimeUnit.SECONDS);
            releaseBcrypt.countDown();
            update.get();
            loginStatus = login.get();
        } finally {
            releaseBcrypt.countDown();
        }

        assertThat(updateCompletedOutsideBcryptLock).isTrue();
        assertThat(loginStatus).isEqualTo(401);
        Account latest = accountRepository.findById(account.id()).orElseThrow();
        assertThat(latest.passwordHash()).isEqualTo(replacementHash);
        assertThat(latest.failedLoginAttempts()).isZero();
        assertThat(authenticationService.login(new LoginCommand(
                account.loginEmail(), "ReplacementPassword1234!", "127.0.0.1"))).isNotNull();
    }

    private void assertParallelPasswordComparisons(String email, String rawPassword) throws Exception {
        CountDownLatch bothInsideBcrypt = new CountDownLatch(2);
        CountDownLatch releaseBcrypt = new CountDownLatch(1);
        CyclicBarrier start = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothInsideBcrypt.countDown();
            if (!releaseBcrypt.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Parallel BCrypt verification was not released.");
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(eq(rawPassword), anyString());

        boolean overlapped;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rejectedLoginAfter(start, email, rawPassword));
            var second = executor.submit(() -> rejectedLoginAfter(start, email, rawPassword));
            overlapped = bothInsideBcrypt.await(2, TimeUnit.SECONDS);
            releaseBcrypt.countDown();
            assertThat(first.get()).isInstanceOf(ApiException.class);
            assertThat(second.get()).isInstanceOf(ApiException.class);
        } finally {
            releaseBcrypt.countDown();
        }
        assertThat(overlapped).isTrue();
    }

    private Throwable rejectedLoginAfter(CyclicBarrier start, String email, String rawPassword) throws Exception {
        start.await();
        try {
            authenticationService.login(new LoginCommand(email, rawPassword, "127.0.0.1"));
            throw new AssertionError("Login unexpectedly succeeded.");
        } catch (ApiException failure) {
            return failure;
        }
    }

    private Account systemAccount() {
        return accountRepository.save(Account.createSystemAdmin(
                "timing-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
    }
}
