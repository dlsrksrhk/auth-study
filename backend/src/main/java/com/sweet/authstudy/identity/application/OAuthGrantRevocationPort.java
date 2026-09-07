package com.sweet.authstudy.identity.application;

import java.time.Instant;

/**
 * Shared application boundary used by identity, HR, and OAuth client mutations.
 */
public interface OAuthGrantRevocationPort {
    /**
     * Acquires OAuth grant locks before an account mutation locks identity rows.
     */
    void lockAccountScope(long accountId);

    void revokeAccount(long accountId, Instant revokedAt);

    void revokeCompany(long companyId, Instant revokedAt);

    void revokeClient(long registeredClientId, Instant revokedAt);
}
