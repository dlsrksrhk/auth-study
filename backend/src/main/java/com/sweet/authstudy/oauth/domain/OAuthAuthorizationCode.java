package com.sweet.authstudy.oauth.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public final class OAuthAuthorizationCode {

    public enum Consumption { CONSUMED, EXPIRED, ALREADY_USED }

    private final Long id;
    private final String authorizationId;
    private final String codeHash;
    private final URI redirectUri;
    private final String codeChallenge;
    private final String nonce;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant usedAt;

    private OAuthAuthorizationCode(Long id, String authorizationId, String codeHash, URI redirectUri,
            String codeChallenge, String nonce, Instant issuedAt, Instant expiresAt, Instant usedAt) {
        this.id = id;
        this.authorizationId = OAuthRefreshToken.requireText(authorizationId, "authorizationId");
        this.codeHash = OAuthRefreshToken.requireSha256(codeHash);
        this.redirectUri = Objects.requireNonNull(redirectUri, "redirectUri");
        this.codeChallenge = requireS256Challenge(codeChallenge);
        this.nonce = nonce;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        this.usedAt = usedAt;
    }

    public static OAuthAuthorizationCode issue(String authorizationId, String codeHash, URI redirectUri,
            String codeChallenge, String nonce, Instant issuedAt, Instant expiresAt) {
        return new OAuthAuthorizationCode(null, authorizationId, codeHash, redirectUri,
                codeChallenge, nonce, issuedAt, expiresAt, null);
    }

    public static OAuthAuthorizationCode restore(Long id, String authorizationId, String codeHash,
            URI redirectUri, String codeChallenge, String nonce, Instant issuedAt, Instant expiresAt,
            Instant usedAt) {
        return new OAuthAuthorizationCode(id, authorizationId, codeHash, redirectUri,
                codeChallenge, nonce, issuedAt, expiresAt, usedAt);
    }

    public Consumption consume(Instant now) {
        Objects.requireNonNull(now, "now");
        if (usedAt != null) {
            return Consumption.ALREADY_USED;
        }
        usedAt = now;
        return expiresAt.isAfter(now) ? Consumption.CONSUMED : Consumption.EXPIRED;
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    public Long id() { return id; }
    public String authorizationId() { return authorizationId; }
    public String codeHash() { return codeHash; }
    public URI redirectUri() { return redirectUri; }
    public String codeChallenge() { return codeChallenge; }
    public String nonce() { return nonce; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant usedAt() { return usedAt; }

    private static String requireS256Challenge(String challenge) {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("PKCE S256 challenge must be a 43-character base64url value.");
        }
        return challenge;
    }
}
