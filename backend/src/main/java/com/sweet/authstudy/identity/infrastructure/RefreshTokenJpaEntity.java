package com.sweet.authstudy.identity.infrastructure;

import java.time.Instant;
import java.util.UUID;

import com.sweet.authstudy.identity.domain.RefreshToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshTokenJpaEntity() {}
    private RefreshTokenJpaEntity(RefreshToken token) {
        this.tokenHash = token.tokenHash();
        this.familyId = token.familyId();
        this.accountId = token.accountId();
        this.issuedAt = token.issuedAt();
        this.expiresAt = token.expiresAt();
        updateFrom(token);
    }
    static RefreshTokenJpaEntity from(RefreshToken token) { return new RefreshTokenJpaEntity(token); }
    void updateFrom(RefreshToken token) {
        this.usedAt = token.usedAt();
        this.revokedAt = token.revokedAt();
    }
    RefreshToken toDomain() {
        return RefreshToken.restore(id, tokenHash, familyId, accountId, issuedAt, expiresAt, usedAt, revokedAt);
    }
}
