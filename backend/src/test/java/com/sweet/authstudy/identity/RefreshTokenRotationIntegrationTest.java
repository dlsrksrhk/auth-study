package com.sweet.authstudy.identity;

import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import com.sweet.authstudy.identity.application.AuthTokens.RefreshResult;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class RefreshTokenRotationIntegrationTest {

    @Autowired AuthenticationService authenticationService;
    @Autowired AccountRepository accountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

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
        LoginResult login = loginActiveSystemAccount();
        RefreshResult rotated = authenticationService.refresh(login.refreshToken());

        authenticationService.logout(rotated.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(rotated.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    private LoginResult loginActiveSystemAccount() {
        String email = "rotate-" + UUID.randomUUID() + "@auth-study.local";
        accountRepository.save(Account.createSystemAdmin(
                email, passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        return authenticationService.login(new LoginCommand(email, "SystemPassword1234!", "127.0.0.1"));
    }
}
