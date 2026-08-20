package com.sweet.authstudy.authorization;

import java.util.Set;

import com.sweet.authstudy.identity.domain.AccountRole;

public record AuthenticatedAccount(
        long accountId, Long companyId, Long userId, Set<AccountRole> roles,
        boolean passwordChangeOnly) {
    public AuthenticatedAccount {
        roles = Set.copyOf(roles);
    }
}
