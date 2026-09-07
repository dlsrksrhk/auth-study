package com.sweet.authstudy.identity.application;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRepository.LoginSnapshot;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

@Service
public class CredentialAuthenticationService {
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$Fw8G.hdiMekZD1U1oRYN4uD2RtUQY4mSCkZIxgn1Kp7R3Ki/6IiS2";

    private final AccountRepository accountRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppSecurityProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthGrantRevocationPort oauthGrants;

    public CredentialAuthenticationService(AccountRepository accountRepository, CompanyRepository companyRepository,
                                           UserRepository userRepository, PasswordEncoder passwordEncoder, AppSecurityProperties properties,
                                           Clock clock, PlatformTransactionManager transactionManager, RefreshTokenRepository refreshTokenRepository,
                                           OAuthGrantRevocationPort oauthGrants) {
        this.accountRepository = accountRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.refreshTokenRepository = refreshTokenRepository;
        this.oauthGrants = oauthGrants;
    }

    public CredentialAuthenticationResult authenticate(Command command) {
        LoginVerification verification = verify(command);
        return authenticate(verification);
    }

    LoginVerification verify(Command command) {
        if (command == null || command.email() == null || command.password() == null) throw unauthenticated();
        LoginVerification verification = verifyPasswordOutsideWriteLock(command);
        if (verification == null) throw unauthenticated();
        return verification;
    }

    CredentialAuthenticationResult authenticate(LoginVerification verification) {
        CredentialAuthenticationResult result = transactions.execute(
                status -> authenticateInTransaction(verification));
        if (result == null) throw unauthenticated();
        return result;
    }

    private LoginVerification verifyPasswordOutsideWriteLock(Command command) {
        Instant now = clock.instant();
        LoginSnapshot snapshot = resolveLoginSnapshot(command.email());
        if (snapshot == null || snapshot.status() != AccountStatus.ACTIVE) {
            performDummyPasswordComparison(command.password());
            return null;
        }
        if (snapshot.lockedUntil() != null && snapshot.lockedUntil().isAfter(now)) {
            performDummyPasswordComparison(command.password());
            return null;
        }
        boolean passwordMatched = passwordEncoder.matches(command.password(), snapshot.passwordHash());
        return new LoginVerification(snapshot.accountId(), snapshot.passwordHash(), passwordMatched);
    }

    private CredentialAuthenticationResult authenticateInTransaction(LoginVerification verification) {
        Instant now = clock.instant();
        if (!verification.passwordMatched()) oauthGrants.lockAccountScope(verification.accountId());
        Account account = accountRepository.findByIdForUpdate(verification.accountId()).orElse(null);
        if (account == null || !account.passwordHash().equals(verification.passwordHash())) return null;
        if (account.status() != AccountStatus.ACTIVE
                || account.lockedUntil() != null && account.lockedUntil().isAfter(now)) return null;
        if (account.lockedUntil() != null) account.clearFailedLogins(now);
        if (!verification.passwordMatched()) {
            int nextFailures = account.failedLoginAttempts() + 1;
            Instant lockedUntil = nextFailures >= properties.loginLock().maxFailures()
                    ? now.plus(properties.loginLock().lockDuration()) : null;
            account.recordFailedLogin(lockedUntil, now);
            accountRepository.save(account);
            if (lockedUntil != null) {
                refreshTokenRepository.revokeAllByAccountId(account.id(), now);
                oauthGrants.revokeAccount(account.id(), now);
            }
            return null;
        }

        HrUser user = account.userId() == null ? null : userRepository.findById(account.userId()).orElse(null);
        if (!loginStateAllowed(account, user)) return null;
        account.clearFailedLogins(now);
        account = accountRepository.save(account);
        return new CredentialAuthenticationResult(account.id(), account.companyId(), account.userId(), account.roles(),
                account.mustChangePassword(), now);
    }

    private LoginSnapshot resolveLoginSnapshot(String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        LoginSnapshot system = accountRepository.findSystemLoginSnapshot(email).orElse(null);
        if (system != null) return system;
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) return null;
        Company company = companyRepository.findByEmailDomain(email.substring(at + 1)).orElse(null);
        if (company == null || company.status() != CompanyStatus.ACTIVE) return null;
        return accountRepository.findCompanyLoginSnapshot(company.id(), email).orElse(null);
    }

    private boolean loginStateAllowed(Account account, HrUser user) {
        if (account.companyId() == null) return true;
        Company company = companyRepository.findById(account.companyId()).orElse(null);
        if (company == null || company.status() != CompanyStatus.ACTIVE) return false;
        if (user == null) return false;
        return account.mustChangePassword() ? user.status() == UserStatus.PENDING || user.status() == UserStatus.ACTIVE
                : user.status() == UserStatus.ACTIVE;
    }

    private void performDummyPasswordComparison(String rawPassword) {
        passwordEncoder.matches(rawPassword, DUMMY_PASSWORD_HASH);
    }

    private ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication failed.");
    }

    public record Command(String email, String password) {
    }

    record LoginVerification(long accountId, String passwordHash, boolean passwordMatched) {
    }

}
