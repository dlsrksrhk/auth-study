package com.sweet.authstudy.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenJpaEntity t set t.revokedAt = :at where t.familyId = :familyId and t.revokedAt is null")
    void revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenJpaEntity t set t.revokedAt = :at where t.accountId = :accountId and t.revokedAt is null")
    void revokeAllByAccountId(@Param("accountId") long accountId, @Param("at") Instant at);
}
