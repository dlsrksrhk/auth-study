package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Objects;

public final class OAuthAccessToken {

    private final Long id;
    private final String authorizationId;
    private final String accessTokenHash;
    private final String jti;
    private final String audience;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant revokedAt;

    private OAuthAccessToken(Long id, String authorizationId, String accessTokenHash, String jti,
            String audience, Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        this.id = id;
        this.authorizationId = OAuthRefreshToken.requireText(authorizationId, "authorizationId");
        this.accessTokenHash = OAuthRefreshToken.requireSha256(accessTokenHash);
        this.jti = OAuthRefreshToken.requireText(jti, "jti");
        this.audience = OAuthRefreshToken.requireText(audience, "audience");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        this.revokedAt = revokedAt;
    }

    public static OAuthAccessToken issue(String authorizationId, String accessTokenHash, String jti,
            String audience, Instant issuedAt, Instant expiresAt) {
        return new OAuthAccessToken(null, authorizationId, accessTokenHash, jti, audience,
                issuedAt, expiresAt, null);
    }

    public static OAuthAccessToken restore(Long id, String authorizationId, String accessTokenHash,
            String jti, String audience, Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        return new OAuthAccessToken(id, authorizationId, accessTokenHash, jti, audience,
                issuedAt, expiresAt, revokedAt);
    }

    public void revoke(Instant now) {
        Objects.requireNonNull(now, "now");
        if (revokedAt == null) revokedAt = now;
    }

    public boolean expiredAt(Instant now) { return !expiresAt.isAfter(Objects.requireNonNull(now)); }
    public Long id() { return id; }
    public String authorizationId() { return authorizationId; }
    public String accessTokenHash() { return accessTokenHash; }
    public String jti() { return jti; }
    public String audience() { return audience; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant revokedAt() { return revokedAt; }
}
