package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthAuthorizationJpaRepository extends JpaRepository<OAuthAuthorizationJpaEntity, String> {
    java.util.Optional<OAuthAuthorizationJpaEntity> findByServerStateHash(String serverStateHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OAuthAuthorizationJpaEntity a where a.id = :id")
    java.util.Optional<OAuthAuthorizationJpaEntity> findByIdForUpdate(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OAuthAuthorizationJpaEntity a
               set a.status = com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.REVOKED,
                   a.revocationReason = :reason, a.revokedAt = :at
             where a.principalAccountId = :accountId and a.revokedAt is null
            """)
    int revokeByAccountId(@Param("accountId") long accountId, @Param("reason") String reason,
            @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OAuthAuthorizationJpaEntity a
               set a.status = com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.REVOKED,
                   a.revocationReason = :reason, a.revokedAt = :at
             where a.registeredClientId = :clientId and a.revokedAt is null
            """)
    int revokeByClientId(@Param("clientId") long clientId, @Param("reason") String reason,
            @Param("at") Instant at);
}
