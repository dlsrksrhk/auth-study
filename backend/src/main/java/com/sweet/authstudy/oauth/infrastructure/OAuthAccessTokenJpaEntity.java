package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_access_token")
class OAuthAccessTokenJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "authorization_id", nullable = false, updatable = false) private String authorizationId;
    @Column(name = "access_token_hash", nullable = false, updatable = false) private String accessTokenHash;
    @Column(nullable = false, updatable = false) private String jti;
    @Column(nullable = false, updatable = false) private String audience;
    @Column(name = "authorized_scopes", nullable = false, updatable = false) private String authorizedScopes;
    @Column(name = "issued_at", nullable = false, updatable = false) private Instant issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected OAuthAccessTokenJpaEntity() { }
    static OAuthAccessTokenJpaEntity from(OAuthAccessToken token) {
        OAuthAccessTokenJpaEntity entity = new OAuthAccessTokenJpaEntity();
        entity.authorizationId = token.authorizationId(); entity.accessTokenHash = token.accessTokenHash();
        entity.jti = token.jti(); entity.audience = token.audience(); entity.issuedAt = token.issuedAt();
        entity.authorizedScopes = token.authorizedScopes().stream().sorted().collect(Collectors.joining(" "));
        entity.expiresAt = token.expiresAt(); entity.revokedAt = token.revokedAt(); return entity;
    }
    void updateFrom(OAuthAccessToken token) { revokedAt = token.revokedAt(); }
    OAuthAccessToken toDomain() { return OAuthAccessToken.restore(id, authorizationId, accessTokenHash, jti,
            audience, scopes(authorizedScopes), issuedAt, expiresAt, revokedAt); }

    private static Set<String> scopes(String value) {
        return Arrays.stream(value.split(" ")).filter(scope -> !scope.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
