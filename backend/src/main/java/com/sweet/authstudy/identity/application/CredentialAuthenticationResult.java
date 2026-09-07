package com.sweet.authstudy.identity.application;

import com.sweet.authstudy.identity.domain.AccountRole;

import java.time.Instant;
import java.util.Set;

public record CredentialAuthenticationResult(
        long accountId,
        Long companyId,
        Long userId,
        Set<AccountRole> roles,
        boolean mustChangePassword,
        Instant authenticatedAt) {
}
