package com.sweet.authstudy.identity.infrastructure;

import java.time.Instant;

import com.sweet.authstudy.identity.domain.AccountStatus;

interface AccountLoginProjection {
    Long getAccountId();
    String getPasswordHash();
    AccountStatus getStatus();
    Instant getLockedUntil();
}
