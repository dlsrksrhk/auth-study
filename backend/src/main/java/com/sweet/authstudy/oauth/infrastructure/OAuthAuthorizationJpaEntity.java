package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "oauth_authorization")
class OAuthAuthorizationJpaEntity {

    @Id
    private String id;
    @Column(name = "registered_client_id", nullable = false)
    private long registeredClientId;
    @Column(nullable = false, updatable = false)
    private UUID subject;
    @Column(name = "principal_account_id", nullable = false, updatable = false)
    private long principalAccountId;
    @Column(name = "company_id", nullable = false, updatable = false)
    private long companyId;
    @Column(name = "authorization_grant_type", nullable = false, updatable = false)
    private String authorizationGrantType;
    @Column(name = "authorized_scopes", nullable = false)
    private String authorizedScopes;
    @Convert(converter = OAuthAuthorizationAttributesConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private OAuthAuthorization.Attributes attributes;
    @Column(name = "server_state_hash")
    private String serverStateHash;
    @Column(name = "authenticated_at", nullable = false, updatable = false)
    private Instant authenticatedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthAuthorization.Status status;
    @Column(name = "revocation_reason")
    private String revocationReason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "id_token_issued_at")
    private Instant idTokenIssuedAt;
    @Column(name = "id_token_expires_at")
    private Instant idTokenExpiresAt;

    protected OAuthAuthorizationJpaEntity() {
    }

    static OAuthAuthorizationJpaEntity from(OAuthAuthorization authorization) {
        OAuthAuthorizationJpaEntity entity = new OAuthAuthorizationJpaEntity();
        entity.id = authorization.id();
        entity.registeredClientId = authorization.registeredClientId();
        entity.subject = authorization.subject();
        entity.principalAccountId = authorization.principalAccountId();
        entity.companyId = authorization.companyId();
        entity.authorizationGrantType = authorization.authorizationGrantType();
        entity.createdAt = authorization.createdAt();
        entity.expiresAt = authorization.expiresAt();
        entity.authenticatedAt = authorization.authenticatedAt();
        entity.updateFrom(authorization);
        return entity;
    }

    void updateFrom(OAuthAuthorization authorization) {
        if (!id.equals(authorization.id()) || registeredClientId != authorization.registeredClientId()) {
            throw new IllegalArgumentException("Authorization identity cannot be changed.");
        }
        authorizedScopes = authorization.authorizedScopes().stream().sorted().collect(Collectors.joining(" "));
        attributes = authorization.attributes();
        serverStateHash = authorization.serverStateHash();
        status = authorization.status();
        revocationReason = authorization.revocationReason();
        revokedAt = authorization.revokedAt();
        idTokenIssuedAt = authorization.idTokenEvidence()
                .map(OAuthAuthorization.IdTokenEvidence::issuedAt).orElse(null);
        idTokenExpiresAt = authorization.idTokenEvidence()
                .map(OAuthAuthorization.IdTokenEvidence::expiresAt).orElse(null);
    }

    void recordIdTokenEvidence(OAuthAuthorization.IdTokenEvidence evidence) {
        if (idTokenIssuedAt != null || idTokenExpiresAt != null) {
            throw new IllegalStateException("ID token issuance evidence already exists.");
        }
        idTokenIssuedAt = evidence.issuedAt();
        idTokenExpiresAt = evidence.expiresAt();
    }

    OAuthAuthorization toDomain(OAuthAuthorizationCode code, OAuthAccessToken accessToken,
                                OAuthRefreshToken refreshToken) {
        Set<String> scopes = authorizedScopes.isBlank() ? Set.of()
                : Arrays.stream(authorizedScopes.split(" ")).collect(Collectors.toUnmodifiableSet());
        return OAuthAuthorization.restore(id, registeredClientId, subject, principalAccountId, companyId,
                authorizationGrantType, scopes, attributes, serverStateHash, authenticatedAt, status,
                revocationReason, createdAt, expiresAt, revokedAt,
                idTokenIssuedAt == null ? null
                        : new OAuthAuthorization.IdTokenEvidence(idTokenIssuedAt, idTokenExpiresAt),
                code, accessToken, refreshToken);
    }
}
