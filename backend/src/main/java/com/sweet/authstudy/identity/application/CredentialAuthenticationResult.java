package com.sweet.authstudy.identity.application;

import java.time.Instant;
import java.util.Set;

import com.sweet.authstudy.identity.domain.AccountRole;

public record CredentialAuthenticationResult(
        long accountId,
        Long companyId,
        Long userId,
        Set<AccountRole> roles,
        boolean mustChangePassword,
        Instant authenticatedAt) {
}
