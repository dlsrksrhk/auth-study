package com.sweet.authstudy.identity.application;

import java.time.Clock;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordGenerator passwordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthGrantRevocationPort oauthGrants;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            PasswordGenerator passwordGenerator,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            OAuthGrantRevocationPort oauthGrants,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.oauthGrants = oauthGrants;
        this.clock = clock;
    }

    @Transactional
    public String resetTemporaryPassword(long accountId) {
        var now = clock.instant();
        oauthGrants.revokeAccount(accountId, now);
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Account was not found."));
        String temporaryPassword = passwordGenerator.generateTemporaryPassword();
        account.resetTemporaryPassword(passwordEncoder.encode(temporaryPassword), now);
        accountRepository.save(account);
        refreshTokenRepository.revokeAllByAccountId(account.id(), now);
        return temporaryPassword;
    }

    @Transactional
    public void assignCompanyAdmin(AuthenticatedAccount actor, long accountId) {
        requireSystemAdmin(actor);
        var now = clock.instant();
        oauthGrants.revokeAccount(accountId, now);
        Account account = target(actor, accountId);
        account.addRole(AccountRole.COMPANY_ADMIN, now);
        accountRepository.save(account);
        refreshTokenRepository.revokeAllByAccountId(account.id(), now);
    }

    @Transactional
    public void revokeCompanyAdmin(AuthenticatedAccount actor, long accountId) {
        requireSystemAdmin(actor);
        var now = clock.instant();
        oauthGrants.revokeAccount(accountId, now);
        Account account = target(actor, accountId);
        account.removeRole(AccountRole.COMPANY_ADMIN, now);
        accountRepository.save(account);
        refreshTokenRepository.revokeAllByAccountId(account.id(), now);
    }

    @Transactional
    public void disableAccount(long accountId) {
        var now = clock.instant();
        oauthGrants.revokeAccount(accountId, now);
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Account was not found."));
        account.changeStatus(AccountStatus.DISABLED, now);
        accountRepository.save(account);
        refreshTokenRepository.revokeAllByAccountId(account.id(), now);
    }

    @Transactional
    public void revokeAllRefreshTokens(long accountId) {
        var now = clock.instant();
        oauthGrants.revokeAccount(accountId, now);
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Account was not found."));
        refreshTokenRepository.revokeAllByAccountId(account.id(), now);
    }

    @Transactional
    public void revokeAllRefreshTokensForCompany(long companyId) {
        var now = clock.instant();
        oauthGrants.revokeCompany(companyId, now);
        for (Account account : accountRepository.findAllByCompanyIdForUpdate(companyId)) {
            refreshTokenRepository.revokeAllByAccountId(account.id(), now);
        }
    }

    private Account target(AuthenticatedAccount actor, long accountId) {
        if (actor.accountId() == accountId && actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A system administrator cannot change its own role.");
        }
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Account was not found."));
        if (account.companyId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "System administrator roles cannot be changed.");
        }
        return account;
    }

    private void requireSystemAdmin(AuthenticatedAccount actor) {
        if (actor == null || !actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "System administrator role is required.");
        }
    }
}
