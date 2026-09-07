package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "oauth_refresh_token")
class OAuthRefreshTokenJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "authorization_id", nullable = false, updatable = false)
    private String authorizationId;
    @Column(name = "refresh_token_hash", nullable = false, updatable = false)
    private String refreshTokenHash;
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;
    @Column(name = "authorized_scopes", nullable = false, updatable = false)
    private String authorizedScopes;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "successor_id")
    private Long successorId;

    protected OAuthRefreshTokenJpaEntity() {
    }

    static OAuthRefreshTokenJpaEntity from(OAuthRefreshToken token) {
        OAuthRefreshTokenJpaEntity entity = new OAuthRefreshTokenJpaEntity();
        entity.authorizationId = token.authorizationId();
        entity.refreshTokenHash = token.refreshTokenHash();
        entity.familyId = token.familyId();
        entity.issuedAt = token.issuedAt();
        entity.expiresAt = token.expiresAt();
        entity.authorizedScopes = token.authorizedScopes().stream().sorted().collect(Collectors.joining(" "));
        entity.usedAt = token.usedAt();
        entity.revokedAt = token.revokedAt();
        entity.successorId = token.successorId();
        return entity;
    }

    void updateFrom(OAuthRefreshToken token) {
        usedAt = token.usedAt();
        revokedAt = token.revokedAt();
        successorId = token.successorId();
    }

    OAuthRefreshToken toDomain() {
        return OAuthRefreshToken.restore(id, authorizationId, refreshTokenHash,
                familyId, scopes(authorizedScopes), issuedAt, expiresAt, usedAt, revokedAt, successorId);
    }

    private static Set<String> scopes(String value) {
        return Arrays.stream(value.split(" ")).filter(scope -> !scope.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
