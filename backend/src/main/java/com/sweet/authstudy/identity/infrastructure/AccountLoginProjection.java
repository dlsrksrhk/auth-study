package com.sweet.authstudy.identity.infrastructure;

import com.sweet.authstudy.identity.domain.AccountStatus;

import java.time.Instant;

interface AccountLoginProjection {
    Long getAccountId();

    String getPasswordHash();

    AccountStatus getStatus();

    Instant getLockedUntil();
}
