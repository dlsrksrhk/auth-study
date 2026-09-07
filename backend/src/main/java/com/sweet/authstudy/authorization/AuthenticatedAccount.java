package com.sweet.authstudy.authorization;

import com.sweet.authstudy.identity.domain.AccountRole;

import java.util.Set;

public record AuthenticatedAccount(
        long accountId, Long companyId, Long userId, Set<AccountRole> roles,
        boolean passwordChangeOnly) {
    public AuthenticatedAccount {
        roles = Set.copyOf(roles);
    }
}
