package com.sweet.authstudy.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByHash(String sha256Hex);
    Optional<Long> findAccountIdByHash(String sha256Hex);
    Optional<RefreshToken> findByHashForUpdate(String sha256Hex);
    RefreshToken save(RefreshToken token);
    void revokeFamily(UUID familyId, Instant revokedAt);
    void revokeAllByAccountId(long accountId, Instant revokedAt);
}
