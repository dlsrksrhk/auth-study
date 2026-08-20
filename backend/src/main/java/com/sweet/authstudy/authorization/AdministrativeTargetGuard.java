package com.sweet.authstudy.authorization;

import java.util.Objects;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AdministrativeTargetGuard {
    private final AccountRepository accountRepository;

    public AdministrativeTargetGuard(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account requireMayMutateUser(AuthenticatedAccount actor, long targetUserId) {
        Account target = accountRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "User account was not found."));
        if (actor == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        }
        if (actor.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            return target;
        }
        if (!Objects.equals(actor.companyId(), target.companyId())
                || target.roles().contains(AccountRole.COMPANY_ADMIN)
                || target.roles().contains(AccountRole.SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Administrator accounts require a system administrator.");
        }
        return target;
    }
}
