package com.sweet.authstudy.identity.application;

import static com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;
import static com.sweet.authstudy.identity.application.AuthCommands.LoginCommand;
import static com.sweet.authstudy.identity.application.AuthTokens.LoginResult;
import static com.sweet.authstudy.identity.application.AuthTokens.RefreshResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.identity.domain.RefreshToken;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthenticationService {
    private final AccountRepository accountRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CredentialAuthenticationService credentialAuthenticationService;
    private final AppSecurityProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthenticationService(AccountRepository accountRepository, CompanyRepository companyRepository,
            UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService,
            CredentialAuthenticationService credentialAuthenticationService,
            AppSecurityProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.credentialAuthenticationService = credentialAuthenticationService;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public LoginResult login(LoginCommand command) {
        CredentialAuthenticationResult credential = credentialAuthenticationService.authenticate(
                new CredentialAuthenticationService.Command(
                        command == null ? null : command.email(), command == null ? null : command.password()));
        Instant now = clock.instant();
        AuthenticatedAccount principal = principal(credential);
        AuthTokens access = jwtTokenService.issue(principal, credential.mustChangePassword());
        String rawRefresh = credential.mustChangePassword() ? null
                : issueRefresh(credential.accountId(), UUID.randomUUID(), now);
        return new LoginResult(access, credential.mustChangePassword(), rawRefresh);
    }

    public RefreshResult refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw unauthenticated();
        String tokenHash = hash(rawToken);
        Long accountId = refreshTokenRepository.findAccountIdByHash(tokenHash).orElse(null);
        if (accountId == null) throw unauthenticated();
        RefreshOutcome outcome = transactions.execute(
                status -> refreshInTransaction(tokenHash, accountId));
        if (outcome == null || outcome.result() == null) throw unauthenticated();
        return outcome.result();
    }

    private RefreshOutcome refreshInTransaction(String tokenHash, long accountId) {
        Instant now = clock.instant();
        Account account = accountRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null) return RefreshOutcome.failure();
        RefreshToken current = refreshTokenRepository.findByHashForUpdate(tokenHash).orElse(null);
        if (current == null || current.accountId() != account.id()) return RefreshOutcome.failure();
        if (current.usedAt() != null) {
            refreshTokenRepository.revokeFamily(current.familyId(), now);
            return RefreshOutcome.failure();
        }
        if (current.revokedAt() != null || current.expiredAt(now)) return RefreshOutcome.failure();
        if (!refreshStateAllowed(account, now)) {
            refreshTokenRepository.revokeAllByAccountId(account.id(), now);
            return RefreshOutcome.failure();
        }
        current.markUsed(now);
        refreshTokenRepository.save(current);
        String successor = issueRefresh(account.id(), current.familyId(), now);
        return RefreshOutcome.success(new RefreshResult(
                jwtTokenService.issue(principal(account, false), false), successor));
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        String tokenHash = hash(rawToken);
        Long accountId = refreshTokenRepository.findAccountIdByHash(tokenHash).orElse(null);
        if (accountId == null) return;
        transactions.executeWithoutResult(status -> {
            Account account = accountRepository.findByIdForUpdate(accountId).orElse(null);
            if (account == null) return;
            RefreshToken locked = refreshTokenRepository.findByHashForUpdate(tokenHash).orElse(null);
            if (locked != null && locked.accountId() == account.id()) {
                refreshTokenRepository.revokeAllByAccountId(account.id(), clock.instant());
            }
        });
    }

    public void changePassword(AuthenticatedAccount principal, ChangePasswordCommand command) {
        if (principal == null || command == null) throw unauthenticated();
        transactions.executeWithoutResult(status -> {
            Account account = accountRepository.findByIdForUpdate(principal.accountId())
                    .orElseThrow(this::unauthenticated);
            if (command.currentPassword() == null
                    || !passwordEncoder.matches(command.currentPassword(), account.passwordHash())) {
                throw unauthenticated();
            }
            validateNewPassword(command.newPassword());
            account.changePassword(passwordEncoder.encode(command.newPassword()), clock.instant());
            accountRepository.save(account);
            refreshTokenRepository.revokeAllByAccountId(account.id(), clock.instant());
        });
    }

    public MeResult me(AuthenticatedAccount principal) {
        if (principal == null) throw unauthenticated();
        return transactions.execute(status -> {
            Account account = accountRepository.findById(principal.accountId()).orElseThrow(this::unauthenticated);
            HrUser user = account.userId() == null ? null : userRepository.findById(account.userId()).orElse(null);
            Company company = account.companyId() == null ? null : companyRepository.findById(account.companyId()).orElse(null);
            return new MeResult(account.id(), account.loginEmail(), account.roles(),
                    user == null ? null : user.code(), user == null ? null : user.name(),
                    company == null ? null : company.code());
        });
    }

    private boolean refreshStateAllowed(Account account, Instant now) {
        if (account.status() != AccountStatus.ACTIVE || account.mustChangePassword()) return false;
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) return false;
        if (account.companyId() == null) return true;
        Company company = companyRepository.findById(account.companyId()).orElse(null);
        HrUser user = userRepository.findById(account.userId()).orElse(null);
        return company != null && company.status() == CompanyStatus.ACTIVE
                && user != null && user.status() == UserStatus.ACTIVE;
    }

    private AuthenticatedAccount principal(Account account, boolean passwordChangeOnly) {
        return new AuthenticatedAccount(account.id(), account.companyId(), account.userId(),
                account.roles(), passwordChangeOnly);
    }

    private AuthenticatedAccount principal(CredentialAuthenticationResult credential) {
        return new AuthenticatedAccount(credential.accountId(), credential.companyId(), credential.userId(),
                credential.roles(), credential.mustChangePassword());
    }

    @EventListener
    public void revokeRefreshTokensOnAccountLock(CredentialAuthenticationService.AccountLocked event) {
        refreshTokenRepository.revokeAllByAccountId(event.accountId(), event.lockedAt());
    }

    private String issueRefresh(long accountId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(RefreshToken.issue(hash(raw), familyId, accountId, now,
                now.plus(properties.jwt().refreshTokenTtl())));
        return raw;
    }

    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void validateNewPassword(String password) {
        int characters = password == null ? 0 : password.codePointCount(0, password.length());
        int utf8Bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (password == null || characters < 12 || characters > 64 || utf8Bytes > 72
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*[0-9].*") || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "New password does not meet policy.");
        }
    }

    private ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication failed.");
    }

    public record MeResult(long accountId, String email, java.util.Set<com.sweet.authstudy.identity.domain.AccountRole> roles,
            String userCode, String userName, String companyCode) {}
    private record RefreshOutcome(RefreshResult result) {
        static RefreshOutcome success(RefreshResult result) { return new RefreshOutcome(result); }
        static RefreshOutcome failure() { return new RefreshOutcome(null); }
    }
}
