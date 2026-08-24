package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;

import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "oauth_client_secret")
class OAuthClientSecretJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private OAuthClientJpaEntity client;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(name = "secret_hint", nullable = false)
    private String secretHint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OAuthClientSecretJpaEntity() {
    }

    private OAuthClientSecretJpaEntity(
            OAuthClientJpaEntity client, OAuthClientSecret secret) {
        this.client = client;
        updateFrom(secret);
    }

    static OAuthClientSecretJpaEntity from(
            OAuthClientJpaEntity client, OAuthClientSecret secret) {
        return new OAuthClientSecretJpaEntity(client, secret);
    }

    Long id() {
        return id;
    }

    void updateFrom(OAuthClientSecret secret) {
        this.secretHash = secret.secretHash();
        this.secretHint = secret.secretHint();
        this.createdAt = secret.createdAt();
        this.expiresAt = secret.expiresAt();
        this.revokedAt = secret.revokedAt();
    }

    OAuthClientSecret toDomain() {
        return OAuthClientSecret.restore(
                id, secretHash, secretHint, createdAt, expiresAt, revokedAt, version);
    }
}
