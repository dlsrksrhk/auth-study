package com.sweet.authstudy.identity.application;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.domain.RefreshToken;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T10:15:30Z");

    @Test
    void issues_hr_tokens_from_an_authenticated_credential_result() {
        CredentialAuthenticationService credentialAuthentication = mock(CredentialAuthenticationService.class);
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        CredentialAuthenticationResult credential = new CredentialAuthenticationResult(
                101L, 202L, 303L, Set.of(AccountRole.USER, AccountRole.COMPANY_ADMIN), false, NOW);
        CredentialAuthenticationService.LoginVerification verification =
                new CredentialAuthenticationService.LoginVerification(101L, "hash", true);
        when(credentialAuthentication.verify(any(CredentialAuthenticationService.Command.class)))
                .thenReturn(verification);
        when(credentialAuthentication.authenticate(verification)).thenReturn(credential);
        when(jwtTokenService.issue(any(AuthenticatedAccount.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new AuthTokens("access-token", NOW.plusSeconds(300)));

        AuthenticationService service = new AuthenticationService(
                mock(AccountRepository.class), mock(CompanyRepository.class), mock(UserRepository.class),
                refreshTokenRepository, mock(PasswordEncoder.class), jwtTokenService, credentialAuthentication,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC), new NoOpTransactionManager(),
                mock(OAuthGrantRevocationPort.class));

        AuthTokens.LoginResult result = service.login(
                new AuthCommands.LoginCommand("admin@acme.local", "Valid1234!", "127.0.0.1"));

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isNotBlank();
        ArgumentCaptor<AuthenticatedAccount> principal = ArgumentCaptor.forClass(AuthenticatedAccount.class);
        verify(jwtTokenService).issue(principal.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(principal.getValue()).isEqualTo(new AuthenticatedAccount(
                101L, 202L, 303L, credential.roles(), false));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    private AppSecurityProperties properties() {
        return new AppSecurityProperties(
                new AppSecurityProperties.Jwt("test-secret", java.time.Duration.ofMinutes(5),
                        java.time.Duration.ofDays(7)),
                null, null, null, null);
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
