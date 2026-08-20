package com.sweet.authstudy.identity;

import static com.sweet.authstudy.support.TestActors.SYSTEM_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.RefreshToken;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import com.sweet.authstudy.identity.application.AuthTokens.RefreshResult;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AccountRevocationIntegrationTest {

    @Autowired CompanyService companyService;
    @Autowired DepartmentService departmentService;
    @Autowired MembershipService membershipService;
    @Autowired UserService userService;
    @Autowired AccountRepository accountRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AuthenticationService authenticationService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void user_lock_temporary_password_reset_and_role_changes_revoke_every_refresh() {
        Fixture fixture = fixture("U001");

        String statusToken = issue(fixture.accountId());
        userService.changeStatus(SYSTEM_ADMIN, fixture.companyCode(), fixture.userCode(),
                UserStatus.LOCKED, fixture.userVersion());
        assertRevoked(statusToken);

        String passwordToken = issue(fixture.accountId());
        userService.resetTemporaryPassword(SYSTEM_ADMIN, fixture.companyCode(), fixture.userCode());
        assertRevoked(passwordToken);

        String grantToken = issue(fixture.accountId());
        userService.assignCompanyAdmin(SYSTEM_ADMIN, fixture.companyCode(), fixture.userCode());
        assertRevoked(grantToken);

        String revokeToken = issue(fixture.accountId());
        userService.revokeCompanyAdmin(SYSTEM_ADMIN, fixture.companyCode(), fixture.userCode());
        assertRevoked(revokeToken);

        Fixture resigned = fixture("U004");
        String resignedToken = issue(resigned.accountId());
        userService.changeStatus(SYSTEM_ADMIN, resigned.companyCode(), resigned.userCode(),
                UserStatus.RESIGNED, resigned.userVersion());
        assertRevoked(resignedToken);
    }

    @Test
    void company_inactivation_revokes_refresh_for_every_company_account() {
        Fixture first = fixture("U001");
        Fixture second = addUser(first.companyCode(), first.domain(), "U002", "E-1002");
        String firstToken = issue(first.accountId());
        String secondToken = issue(second.accountId());

        var company = companyService.find(SYSTEM_ADMIN, first.companyCode());
        companyService.update(SYSTEM_ADMIN, first.companyCode(),
                new UpdateCompanyCommand(company.name(), CompanyStatus.INACTIVE, company.version()));

        assertRevoked(firstToken);
        assertRevoked(secondToken);
    }

    @Test
    void company_inactivation_racing_refresh_rotation_leaves_no_successor_alive() throws Exception {
        Fixture fixture = fixture("U003");
        var account = accountRepository.findById(fixture.accountId()).orElseThrow();
        account.changePassword(passwordEncoder.encode("ChangedPassword1234!"), clock.instant());
        accountRepository.save(account);
        LoginResult login = authenticationService.login(new LoginCommand(
                account.loginEmail(), "ChangedPassword1234!", "127.0.0.1"));

        installDelayedRefreshInsert();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var rotation = executor.submit(() -> authenticationService.refresh(login.refreshToken()));
            awaitDelayedRefreshInsert();
            var company = companyService.find(SYSTEM_ADMIN, fixture.companyCode());
            companyService.update(SYSTEM_ADMIN, fixture.companyCode(),
                    new UpdateCompanyCommand(company.name(), CompanyStatus.INACTIVE, company.version()));
            RefreshResult successor = rotation.get();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> authenticationService.refresh(successor.refreshToken()))
                    .isInstanceOf(ApiException.class);
        } finally {
            removeDelayedRefreshInsert();
        }
    }

    private Fixture fixture(String userCode) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = "R" + suffix.toUpperCase();
        String domain = suffix + ".revoke.example";
        companyService.create(SYSTEM_ADMIN,
                new CreateCompanyCommand(companyCode, "Revoke Co", domain));
        departmentService.create(SYSTEM_ADMIN,
                new CreateDepartmentCommand(companyCode, "DEV", "Development", null));
        return addUser(companyCode, domain, userCode, "E-1001");
    }

    private Fixture addUser(String companyCode, String domain, String userCode, String employeeNumber) {
        var created = userService.create(SYSTEM_ADMIN, new CreateUserCommand(
                companyCode, userCode, employeeNumber, "Kim", userCode.toLowerCase() + "@" + domain,
                "010-0000-0000", LocalDate.of(2026, 8, 20), "Seoul", null, "EMPLOYEE"));
        membershipService.assign(SYSTEM_ADMIN, new AssignMembershipCommand(
                companyCode, userCode, "DEV", DepartmentRole.MEMBER, true, clock.instant()));
        var active = userService.changeStatus(SYSTEM_ADMIN, companyCode, userCode,
                UserStatus.ACTIVE, created.user().version());
        long accountId = accountRepository.findByUserId(created.user().id()).orElseThrow().id();
        return new Fixture(companyCode, domain, userCode, active.version(), accountId);
    }

    private String issue(long accountId) {
        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        refreshTokenRepository.save(RefreshToken.issue(
                hash, UUID.randomUUID(), accountId, clock.instant(), clock.instant().plusSeconds(3600)));
        return hash;
    }

    private void assertRevoked(String hash) {
        assertThat(refreshTokenRepository.findByHash(hash).orElseThrow().revokedAt()).isNotNull();
    }

    private void installDelayedRefreshInsert() {
        jdbc.execute("CREATE OR REPLACE FUNCTION task8_delay_refresh_insert() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER task8_delay_refresh_insert_trigger BEFORE INSERT ON refresh_tokens "
                + "FOR EACH ROW EXECUTE FUNCTION task8_delay_refresh_insert()");
    }

    private void removeDelayedRefreshInsert() {
        jdbc.execute("DROP TRIGGER IF EXISTS task8_delay_refresh_insert_trigger ON refresh_tokens");
        jdbc.execute("DROP FUNCTION IF EXISTS task8_delay_refresh_insert()");
    }

    private void awaitDelayedRefreshInsert() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Integer activeInserts = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity "
                            + "where pid <> pg_backend_pid() and state = 'active' "
                            + "and query like 'insert into refresh_tokens%'",
                    Integer.class);
            if (activeInserts != null && activeInserts > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Refresh insert did not reach the delay trigger.");
    }

    private record Fixture(
            String companyCode, String domain, String userCode, long userVersion, long accountId) {}
}
