package com.sweet.authstudy.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sweet.authstudy.identity.domain.RefreshToken;
import com.sweet.authstudy.identity.domain.RefreshTokenRepository;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {
    private final RefreshTokenJpaRepository repository;
    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository repository) { this.repository = repository; }

    @Override public Optional<RefreshToken> findByHash(String hash) {
        return repository.findByTokenHash(hash).map(RefreshTokenJpaEntity::toDomain);
    }
    @Override public Optional<Long> findAccountIdByHash(String hash) {
        return repository.findAccountIdByTokenHash(hash);
    }
    @Override public Optional<RefreshToken> findByHashForUpdate(String hash) {
        return repository.findByTokenHashForUpdate(hash).map(RefreshTokenJpaEntity::toDomain);
    }
    @Override public RefreshToken save(RefreshToken token) {
        RefreshTokenJpaEntity entity = token.id() == null ? RefreshTokenJpaEntity.from(token)
                : repository.findById(token.id()).orElseThrow(() -> new IllegalStateException("Refresh token does not exist."));
        if (token.id() != null) entity.updateFrom(token);
        return repository.saveAndFlush(entity).toDomain();
    }
    @Override public void revokeFamily(UUID familyId, Instant revokedAt) { repository.revokeFamily(familyId, revokedAt); }
    @Override public void revokeAllByAccountId(long accountId, Instant revokedAt) { repository.revokeAllByAccountId(accountId, revokedAt); }
}
