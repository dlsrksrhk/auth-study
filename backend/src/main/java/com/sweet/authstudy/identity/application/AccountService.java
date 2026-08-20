package com.sweet.authstudy.identity.application;

import java.time.Clock;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
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
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            PasswordGenerator passwordGenerator,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordGenerator = passwordGenerator;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public String resetTemporaryPassword(long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Account was not found."));
        String temporaryPassword = passwordGenerator.generateTemporaryPassword();
        account.resetTemporaryPassword(passwordEncoder.encode(temporaryPassword), clock.instant());
        accountRepository.save(account);
        return temporaryPassword;
    }

    @Transactional
    public void assignCompanyAdmin(AuthenticatedAccount actor, long accountId) {
        requireSystemAdmin(actor);
        Account account = target(actor, accountId);
        account.addRole(AccountRole.COMPANY_ADMIN, clock.instant());
        accountRepository.save(account);
    }

    @Transactional
    public void revokeCompanyAdmin(AuthenticatedAccount actor, long accountId) {
        requireSystemAdmin(actor);
        Account account = target(actor, accountId);
        account.removeRole(AccountRole.COMPANY_ADMIN, clock.instant());
        accountRepository.save(account);
    }

    private Account target(AuthenticatedAccount actor, long accountId) {
        if (actor.accountId() == accountId && actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A system administrator cannot change its own role.");
        }
        Account account = accountRepository.findById(accountId)
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
