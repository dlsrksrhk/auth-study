package com.sweet.authstudy.identity;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import com.sweet.authstudy.identity.application.AuthTokens.RefreshResult;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;
import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class RefreshTokenRotationIntegrationTest {

    @Autowired
    AuthenticationService authenticationService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    Clock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void refresh_rotates_token_and_reuse_revokes_family() {
        String email = "rotate-" + UUID.randomUUID() + "@auth-study.local";
        accountRepository.save(Account.createSystemAdmin(
                email, passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        LoginResult login = authenticationService.login(
                new LoginCommand(email, "SystemPassword1234!", "127.0.0.1"));

        RefreshResult rotated = authenticationService.refresh(login.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThatThrownBy(() -> authenticationService.refresh(login.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authenticationService.refresh(rotated.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrent_refresh_submissions_cannot_both_mint_successors() throws Exception {
        LoginResult login = loginActiveSystemAccount();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Object> submission = () -> {
            start.await();
            try {
                return authenticationService.refresh(login.refreshToken());
            } catch (ApiException exception) {
                return exception;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(submission);
            var second = executor.submit(submission);
            Object firstResult = first.get();
            Object secondResult = second.get();

            assertThat(java.util.List.of(firstResult, secondResult).stream()
                    .filter(RefreshResult.class::isInstance)).hasSize(1);
            assertThat(java.util.List.of(firstResult, secondResult).stream()
                    .filter(ApiException.class::isInstance)).hasSize(1);
            RefreshResult successor = (RefreshResult) java.util.List.of(firstResult, secondResult).stream()
                    .filter(RefreshResult.class::isInstance).findFirst().orElseThrow();
            assertThatThrownBy(() -> authenticationService.refresh(successor.refreshToken()))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void logout_revokes_the_refresh_family() {
        LoginResult firstFamily = loginActiveSystemAccount();
        LoginResult secondFamily = authenticationService.login(new LoginCommand(
                accountRepository.findById(subjectId(firstFamily)).orElseThrow().loginEmail(),
                "SystemPassword1234!", "127.0.0.1"));

        authenticationService.logout(firstFamily.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(firstFamily.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authenticationService.refresh(secondFamily.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void account_lock_revokes_every_refresh_family_and_blocks_refresh_while_locked() {
        LoginResult firstFamily = loginActiveSystemAccount();
        Account account = accountRepository.findById(subjectId(firstFamily)).orElseThrow();
        LoginResult secondFamily = authenticationService.login(new LoginCommand(
                account.loginEmail(), "SystemPassword1234!", "127.0.0.1"));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authenticationService.login(
                    new LoginCommand(account.loginEmail(), "wrong", "127.0.0.1")))
                    .isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> authenticationService.refresh(firstFamily.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authenticationService.refresh(secondFamily.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void login_racing_a_new_lock_does_not_leave_its_refresh_token_alive() throws Exception {
        LoginResult existing = loginActiveSystemAccount();
        Account account = accountRepository.findById(subjectId(existing)).orElseThrow();

        installDelayedRefreshInsert(10);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var successfulLogin = executor.submit(() -> authenticationService.login(new LoginCommand(
                    account.loginEmail(), "SystemPassword1234!", "127.0.0.1")));
            awaitDelayedRefreshInsert();
            jdbc.update("update accounts set failed_login_attempts = 4, locked_until = null where id = ?", account.id());

            assertThatThrownBy(() -> authenticationService.login(new LoginCommand(
                    account.loginEmail(), "wrong", "127.0.0.1"))).isInstanceOf(ApiException.class);
            LoginResult raced = successfulLogin.get(20, TimeUnit.SECONDS);

            assertThatThrownBy(() -> authenticationService.refresh(raced.refreshToken()))
                    .isInstanceOf(ApiException.class);
        } finally {
            removeDelayedRefreshInsert();
        }
    }

    @Test
    void password_change_racing_rotation_leaves_no_successor_alive() throws Exception {
        LoginResult login = loginActiveSystemAccount();
        Account account = accountRepository.findById(subjectId(login)).orElseThrow();
        installDelayedRefreshInsert();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var rotation = executor.submit(() -> authenticationService.refresh(login.refreshToken()));
            awaitDelayedRefreshInsert();
            authenticationService.changePassword(
                    new AuthenticatedAccount(account.id(), null, null, account.roles(), false),
                    new ChangePasswordCommand("SystemPassword1234!", "ChangedPassword1234!"));
            RefreshResult successor = rotation.get();

            assertThatThrownBy(() -> authenticationService.refresh(successor.refreshToken()))
                    .isInstanceOf(ApiException.class);
        } finally {
            removeDelayedRefreshInsert();
        }
    }

    @Test
    void reuse_detection_racing_successor_rotation_revokes_the_late_successor() throws Exception {
        LoginResult login = loginActiveSystemAccount();
        RefreshResult successor = authenticationService.refresh(login.refreshToken());
        installDelayedRefreshInsert();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var successorRotation = executor.submit(() -> authenticationService.refresh(successor.refreshToken()));
            awaitDelayedRefreshInsert();
            assertThatThrownBy(() -> authenticationService.refresh(login.refreshToken()))
                    .isInstanceOf(ApiException.class);
            RefreshResult lateSuccessor = successorRotation.get();

            assertThatThrownBy(() -> authenticationService.refresh(lateSuccessor.refreshToken()))
                    .isInstanceOf(ApiException.class);
        } finally {
            removeDelayedRefreshInsert();
        }
    }

    private LoginResult loginActiveSystemAccount() {
        String email = "rotate-" + UUID.randomUUID() + "@auth-study.local";
        accountRepository.save(Account.createSystemAdmin(
                email, passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        return authenticationService.login(new LoginCommand(email, "SystemPassword1234!", "127.0.0.1"));
    }

    private long subjectId(LoginResult login) {
        return Long.parseLong(jwtDecoder.decode(login.accessToken()).getSubject());
    }

    private void installDelayedRefreshInsert() {
        installDelayedRefreshInsert(2);
    }

    private void installDelayedRefreshInsert(int seconds) {
        jdbc.execute("CREATE OR REPLACE FUNCTION task5_delay_refresh_insert() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(" + seconds + "); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER task5_delay_refresh_insert_trigger BEFORE INSERT ON refresh_tokens "
                + "FOR EACH ROW EXECUTE FUNCTION task5_delay_refresh_insert()");
    }

    private void removeDelayedRefreshInsert() {
        jdbc.execute("DROP TRIGGER IF EXISTS task5_delay_refresh_insert_trigger ON refresh_tokens");
        jdbc.execute("DROP FUNCTION IF EXISTS task5_delay_refresh_insert()");
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
}
