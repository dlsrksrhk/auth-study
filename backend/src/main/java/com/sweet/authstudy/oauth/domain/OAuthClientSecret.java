package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Objects;

public final class OAuthClientSecret {

    private final Long id;
    private final String secretHash;
    private final String secretHint;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final long version;

    private OAuthClientSecret(
            Long id,
            String secretHash,
            String secretHint,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version) {
        this.id = id;
        this.secretHash = Objects.requireNonNull(secretHash);
        this.secretHint = Objects.requireNonNull(secretHint);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public static OAuthClientSecret create(
            String secretHash, String secretHint, Instant createdAt, Instant expiresAt) {
        return new OAuthClientSecret(
                null, secretHash, secretHint, createdAt, expiresAt, null, 0);
    }

    public static OAuthClientSecret restore(
            Long id,
            String secretHash,
            String secretHint,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version) {
        return new OAuthClientSecret(
                id, secretHash, secretHint, createdAt, expiresAt, revokedAt, version);
    }

    public Long id() { return id; }
    public String secretHash() { return secretHash; }
    public String secretHint() { return secretHint; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant revokedAt() { return revokedAt; }
    public long version() { return version; }
}
