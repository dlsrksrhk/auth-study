package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "oauth_signing_key")
class OAuthSigningKeyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private String kid;
    @Column(nullable = false, updatable = false)
    private String algorithm;
    @Column(name = "encrypted_private_material", nullable = false, updatable = false)
    private byte[] encryptedPrivateMaterial;
    @Column(name = "public_jwk", nullable = false, updatable = false)
    private String publicJwk;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthSigningKey.Status status;
    @Column(name = "activated_at", nullable = false, updatable = false)
    private Instant activatedAt;
    @Column(name = "retired_at")
    private Instant retiredAt;

    protected OAuthSigningKeyJpaEntity() {
    }

    static OAuthSigningKeyJpaEntity from(OAuthSigningKey key) {
        OAuthSigningKeyJpaEntity entity = new OAuthSigningKeyJpaEntity();
        entity.kid = key.kid();
        entity.algorithm = key.algorithm();
        entity.encryptedPrivateMaterial = key.encryptedPrivateMaterial();
        entity.publicJwk = key.publicJwk();
        entity.status = key.status();
        entity.activatedAt = key.activatedAt();
        entity.retiredAt = key.retiredAt();
        return entity;
    }

    void updateLifecycle(OAuthSigningKey key) {
        if (!id.equals(key.id()) || !kid.equals(key.kid())) {
            throw new IllegalArgumentException("OAuth signing key identity cannot be changed.");
        }
        status = key.status();
        retiredAt = key.retiredAt();
    }

    OAuthSigningKey toDomain() {
        return OAuthSigningKey.restore(id, kid, algorithm, publicJwk,
                encryptedPrivateMaterial, status, activatedAt, retiredAt);
    }
}
