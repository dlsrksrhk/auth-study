package com.sweet.authstudy.identity;

import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthenticationTimingIntegrationTest {
    @Autowired AuthenticationService authenticationService;
    @Autowired AccountRepository accountRepository;
    @Autowired Clock clock;
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

    private Account systemAccount() {
        return accountRepository.save(Account.createSystemAdmin(
                "timing-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
    }
}
