package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import jakarta.persistence.*;

import java.net.URI;
import java.time.Instant;

@Entity
@Table(name = "oauth_authorization_code")
class OAuthAuthorizationCodeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "authorization_id", nullable = false, updatable = false)
    private String authorizationId;
    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;
    @Column(name = "redirect_uri", nullable = false, updatable = false)
    private String redirectUri;
    @Column(name = "code_challenge", nullable = false, updatable = false)
    private String codeChallenge;
    @Column(name = "code_challenge_method", nullable = false, updatable = false)
    private String codeChallengeMethod;
    @Column(updatable = false)
    private String nonce;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;

    protected OAuthAuthorizationCodeJpaEntity() {
    }

    static OAuthAuthorizationCodeJpaEntity from(OAuthAuthorizationCode code) {
        OAuthAuthorizationCodeJpaEntity entity = new OAuthAuthorizationCodeJpaEntity();
        entity.authorizationId = code.authorizationId();
        entity.codeHash = code.codeHash();
        entity.redirectUri = code.redirectUri().toString();
        entity.codeChallenge = code.codeChallenge();
        entity.codeChallengeMethod = "S256";
        entity.nonce = code.nonce();
        entity.issuedAt = code.issuedAt();
        entity.expiresAt = code.expiresAt();
        entity.usedAt = code.usedAt();
        return entity;
    }

    void updateFrom(OAuthAuthorizationCode code) {
        usedAt = code.usedAt();
    }

    OAuthAuthorizationCode toDomain() {
        return OAuthAuthorizationCode.restore(id, authorizationId, codeHash,
                URI.create(redirectUri), codeChallenge, nonce, issuedAt, expiresAt, usedAt);
    }
}
