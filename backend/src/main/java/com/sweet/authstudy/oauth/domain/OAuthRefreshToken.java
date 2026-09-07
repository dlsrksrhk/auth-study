package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OAuthRefreshToken {

    private final Long id;
    private final String authorizationId;
    private final String refreshTokenHash;
    private final UUID familyId;
    private final Set<String> authorizedScopes;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant usedAt;
    private Instant revokedAt;
    private Long successorId;

    private OAuthRefreshToken(Long id, String authorizationId, String refreshTokenHash, UUID familyId,
                              Set<String> authorizedScopes, Instant issuedAt, Instant expiresAt,
                              Instant usedAt, Instant revokedAt, Long successorId) {
        this.id = id;
        this.authorizationId = requireText(authorizationId, "authorizationId");
        this.refreshTokenHash = requireSha256(refreshTokenHash);
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.authorizedScopes = requireScopes(authorizedScopes);
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
        this.successorId = successorId;
    }

    public static OAuthRefreshToken issue(String authorizationId, String refreshTokenHash, UUID familyId,
                                          Instant issuedAt, Instant expiresAt) {
        return issue(authorizationId, refreshTokenHash, familyId, Set.of("openid"), issuedAt, expiresAt);
    }

    public static OAuthRefreshToken issue(String authorizationId, String refreshTokenHash, UUID familyId,
                                          Set<String> authorizedScopes, Instant issuedAt, Instant expiresAt) {
        return new OAuthRefreshToken(null, authorizationId, refreshTokenHash, familyId,
                authorizedScopes, issuedAt, expiresAt, null, null, null);
    }

    public static OAuthRefreshToken restore(Long id, String authorizationId, String refreshTokenHash,
                                            UUID familyId, Instant issuedAt, Instant expiresAt, Instant usedAt, Instant revokedAt,
                                            Long successorId) {
        return restore(id, authorizationId, refreshTokenHash, familyId, Set.of("openid"),
                issuedAt, expiresAt, usedAt, revokedAt, successorId);
    }

    public static OAuthRefreshToken restore(Long id, String authorizationId, String refreshTokenHash,
                                            UUID familyId, Set<String> authorizedScopes, Instant issuedAt, Instant expiresAt,
                                            Instant usedAt, Instant revokedAt, Long successorId) {
        return new OAuthRefreshToken(id, authorizationId, refreshTokenHash, familyId,
                authorizedScopes, issuedAt, expiresAt, usedAt, revokedAt, successorId);
    }

    public void markUsed(Instant usedAt, OAuthRefreshToken successor) {
        Objects.requireNonNull(usedAt, "usedAt");
        Objects.requireNonNull(successor, "successor");
        if (this.usedAt != null) {
            throw new IllegalStateException("Refresh token was already used.");
        }
        if (successor.id == null) {
            throw new IllegalArgumentException("Successor must be persisted before it is linked.");
        }
        if (!familyId.equals(successor.familyId)) {
            throw new IllegalArgumentException("Successor must belong to the same refresh family.");
        }
        if (!authorizationId.equals(successor.authorizationId)) {
            throw new IllegalArgumentException("Successor must belong to the same authorization.");
        }
        if (!expiresAt.equals(successor.expiresAt)) {
            throw new IllegalArgumentException("Successor must preserve the refresh family absolute expiry.");
        }
        if (!authorizedScopes.containsAll(successor.authorizedScopes)) {
            throw new IllegalArgumentException("Successor scopes cannot expand the refresh grant.");
        }
        if (id != null && id.equals(successor.id)) {
            throw new IllegalArgumentException("A refresh token cannot succeed itself.");
        }
        if (successor.issuedAt.isBefore(usedAt)) {
            throw new IllegalArgumentException("Successor cannot be issued before the current token is used.");
        }
        this.usedAt = usedAt;
        this.successorId = successor.id;
    }

    public void revoke(Instant revokedAt) {
        Objects.requireNonNull(revokedAt, "revokedAt");
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
    }

    public void revokeFamilyAfterReuse(Collection<OAuthRefreshToken> familyTokens, Instant detectedAt) {
        Objects.requireNonNull(familyTokens, "familyTokens");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (usedAt == null) {
            throw new IllegalStateException("Only a used refresh token can trigger reuse revocation.");
        }
        familyTokens.stream()
                .filter(token -> familyId.equals(token.familyId))
                .forEach(token -> token.revoke(detectedAt));
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    public Long id() {
        return id;
    }

    public String authorizationId() {
        return authorizationId;
    }

    public String refreshTokenHash() {
        return refreshTokenHash;
    }

    public UUID familyId() {
        return familyId;
    }

    public Set<String> authorizedScopes() {
        return authorizedScopes;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Long successorId() {
        return successorId;
    }

    static String requireSha256(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A lowercase SHA-256 hash is required.");
        }
        return hash;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Set<String> requireScopes(Set<String> scopes) {
        Set<String> snapshot = Set.copyOf(Objects.requireNonNull(scopes, "authorizedScopes"));
        if (snapshot.isEmpty() || snapshot.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("authorizedScopes must contain normalized scopes");
        }
        return snapshot;
    }
}
