package com.sweet.authstudy.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RefreshToken {
    private final Long id;
    private final String tokenHash;
    private final UUID familyId;
    private final long accountId;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant usedAt;
    private Instant revokedAt;

    private RefreshToken(Long id, String tokenHash, UUID familyId, long accountId,
            Instant issuedAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        this.id = id;
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.familyId = Objects.requireNonNull(familyId);
        this.accountId = accountId;
        this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
    }

    public static RefreshToken issue(String tokenHash, UUID familyId, long accountId,
            Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(null, tokenHash, familyId, accountId, issuedAt, expiresAt, null, null);
    }

    public static RefreshToken restore(Long id, String tokenHash, UUID familyId, long accountId,
            Instant issuedAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return new RefreshToken(id, tokenHash, familyId, accountId, issuedAt, expiresAt, usedAt, revokedAt);
    }

    public void markUsed(Instant now) { this.usedAt = Objects.requireNonNull(now); }
    public void revoke(Instant now) { this.revokedAt = Objects.requireNonNull(now); }
    public boolean expiredAt(Instant now) { return !expiresAt.isAfter(now); }
    public Long id() { return id; }
    public String tokenHash() { return tokenHash; }
    public UUID familyId() { return familyId; }
    public long accountId() { return accountId; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant usedAt() { return usedAt; }
    public Instant revokedAt() { return revokedAt; }
}
