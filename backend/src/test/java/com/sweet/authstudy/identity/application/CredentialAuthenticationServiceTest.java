package com.sweet.authstudy.identity.application;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.*;
import com.sweet.authstudy.identity.domain.AccountRepository.LoginSnapshot;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T10:15:30Z");
    private static final long ACCOUNT_ID = 101L;
    private static final long COMPANY_ID = 202L;
    private static final long USER_ID = 303L;
    private static final String EMAIL = "admin@acme.local";
    private static final String PASSWORD = "Valid1234!";
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$Fw8G.hdiMekZD1U1oRYN4uD2RtUQY4mSCkZIxgn1Kp7R3Ki/6IiS2";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final OAuthGrantRevocationPort oauthGrants = mock(OAuthGrantRevocationPort.class);
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private CredentialAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new CredentialAuthenticationService(
                accountRepository, companyRepository, userRepository, passwordEncoder,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC), new NoOpTransactionManager(),
                refreshTokenRepository, oauthGrants);
    }

    @Test
    void authenticates_an_active_company_account_without_issuing_tokens() {
        Account account = companyAccount(false, UserStatus.ACTIVE);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(activeCompany()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(accountRepository.save(account)).thenReturn(account);

        CredentialAuthenticationResult result = service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, PASSWORD));

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.companyId()).isEqualTo(COMPANY_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.roles()).contains(AccountRole.COMPANY_ADMIN);
        assertThat(result.mustChangePassword()).isFalse();
        assertThat(result.authenticatedAt()).isEqualTo(NOW);
    }

    @Test
    void records_a_failed_attempt_for_an_incorrect_password() {
        Account account = companyAccount(false, UserStatus.ACTIVE);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertUnauthenticated(() -> service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, "incorrect")));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().failedLoginAttempts()).isEqualTo(1);
        assertThat(saved.getValue().lockedUntil()).isNull();
    }

    @Test
    void rejects_a_locked_account_after_a_dummy_bcrypt_comparison() {
        Account account = Account.restore(ACCOUNT_ID, null, null, EMAIL, passwordEncoder.encode(PASSWORD),
                AccountStatus.ACTIVE, false, 5, NOW.plusSeconds(60),
                java.util.Set.of(AccountRole.SYSTEM_ADMIN), 0, NOW, NOW);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.of(snapshot(account)));

        assertUnauthenticated(() -> service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, "incorrect")));

        verify(passwordEncoder).matches("incorrect", DUMMY_PASSWORD_HASH);
    }

    @Test
    void locks_on_the_fifth_failed_attempt_and_directly_revokes_hr_and_oauth_grants() {
        Account account = companyAccount(false, UserStatus.ACTIVE);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertUnauthenticated(() -> service.authenticate(
                    new CredentialAuthenticationService.Command(EMAIL, "incorrect")));
        }

        assertThat(account.failedLoginAttempts()).isEqualTo(5);
        assertThat(account.lockedUntil()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(15)));
        verify(oauthGrants, org.mockito.Mockito.times(5)).lockAccountScope(ACCOUNT_ID);
        verify(refreshTokenRepository).revokeAllByAccountId(ACCOUNT_ID, NOW);
        verify(oauthGrants).revokeAccount(ACCOUNT_ID, NOW);
    }

    @Test
    void discards_a_password_match_when_the_locked_row_has_a_new_password_hash() {
        Account snapshotAccount = companyAccount(false, UserStatus.ACTIVE);
        Account changedAccount = Account.restore(ACCOUNT_ID, COMPANY_ID, USER_ID, EMAIL,
                passwordEncoder.encode("Replacement1234!"), AccountStatus.ACTIVE, false, 0, null,
                java.util.Set.of(AccountRole.USER, AccountRole.COMPANY_ADMIN), 0, NOW, NOW);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(snapshotAccount)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(changedAccount));

        assertUnauthenticated(() -> service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, PASSWORD)));

        org.mockito.Mockito.verifyNoInteractions(userRepository);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void rejects_an_inactive_company_after_verifying_credentials() {
        Account account = companyAccount(false, UserStatus.ACTIVE);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(Company.restore(
                COMPANY_ID, "ACME", "Acme", "acme.local", CompanyStatus.INACTIVE, 0, NOW, NOW)));

        assertUnauthenticated(() -> service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, PASSWORD)));
    }

    @Test
    void rejects_an_inactive_user_after_verifying_credentials() {
        Account account = companyAccount(false, UserStatus.LOCKED);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(activeCompany()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.LOCKED)));

        assertUnauthenticated(() -> service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, PASSWORD)));
    }

    @Test
    void returns_must_change_password_for_a_pending_company_user() {
        Account account = companyAccount(true, UserStatus.PENDING);
        when(accountRepository.findSystemLoginSnapshot(EMAIL)).thenReturn(Optional.empty());
        when(companyRepository.findByEmailDomain("acme.local")).thenReturn(Optional.of(activeCompany()));
        when(accountRepository.findCompanyLoginSnapshot(COMPANY_ID, EMAIL))
                .thenReturn(Optional.of(snapshot(account)));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(activeCompany()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.PENDING)));
        when(accountRepository.save(account)).thenReturn(account);

        CredentialAuthenticationResult result = service.authenticate(
                new CredentialAuthenticationService.Command(EMAIL, PASSWORD));

        assertThat(result.mustChangePassword()).isTrue();
    }

    private Account companyAccount(boolean mustChangePassword, UserStatus userStatus) {
        return Account.restore(ACCOUNT_ID, COMPANY_ID, USER_ID, EMAIL, passwordEncoder.encode(PASSWORD),
                AccountStatus.ACTIVE, mustChangePassword, 0, null,
                java.util.Set.of(AccountRole.USER, AccountRole.COMPANY_ADMIN), 0, NOW, NOW);
    }

    private LoginSnapshot snapshot(Account account) {
        return new LoginSnapshot(account.id(), account.passwordHash(), account.status(), account.lockedUntil());
    }

    private Company activeCompany() {
        return Company.restore(COMPANY_ID, "ACME", "Acme", "acme.local", CompanyStatus.ACTIVE, 0, NOW, NOW);
    }

    private HrUser user(UserStatus status) {
        return HrUser.restore(USER_ID, COMPANY_ID, "U001", "E001", "Kim", "010-0000-0000",
                java.time.LocalDate.parse("2020-01-01"), "Seoul", null, 1L, status, 0, NOW, NOW);
    }

    private AppSecurityProperties properties() {
        return new AppSecurityProperties(null, null,
                new AppSecurityProperties.LoginLock(5, java.time.Duration.ofMinutes(15)), null, null);
    }

    private void assertUnauthenticated(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
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
