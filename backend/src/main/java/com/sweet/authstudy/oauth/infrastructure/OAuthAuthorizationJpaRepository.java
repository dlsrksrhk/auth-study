package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.List;

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

    /** Common transaction-scoped sentinel acquired before any refresh/auth row lock for a grant. */
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(:authorizationId, 1181783497276652981))",
            nativeQuery = true)
    void lockGrantScope(@Param("authorizationId") String authorizationId);

    @Query("select a.id from OAuthAuthorizationJpaEntity a where a.principalAccountId = :accountId order by a.id")
    List<String> findIdsByAccountId(@Param("accountId") long accountId);

    @Query("select a.id from OAuthAuthorizationJpaEntity a where a.companyId = :companyId order by a.id")
    List<String> findIdsByCompanyId(@Param("companyId") long companyId);

    @Query("select a.id from OAuthAuthorizationJpaEntity a where a.registeredClientId = :clientId order by a.id")
    List<String> findIdsByClientId(@Param("clientId") long clientId);

    @Query("""
            select a.id from OAuthAuthorizationJpaEntity a
             where a.principalAccountId = :accountId and a.registeredClientId = :clientId
             order by a.id
            """)
    List<String> findIdsByAccountIdAndClientId(
            @Param("accountId") long accountId, @Param("clientId") long clientId);

    @Query(value = "select id from oauth_authorization where id in (:ids) order by id for update",
            nativeQuery = true)
    List<String> lockByIds(@Param("ids") List<String> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OAuthAuthorizationJpaEntity a
               set a.status = com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.REVOKED,
                   a.revocationReason = :reason, a.revokedAt = :at
             where a.id = :authorizationId and a.revokedAt is null
            """)
    int revokeById(@Param("authorizationId") String authorizationId,
            @Param("reason") String reason, @Param("at") Instant at);

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
             where a.companyId = :companyId and a.revokedAt is null
            """)
    int revokeByCompanyId(@Param("companyId") long companyId, @Param("reason") String reason,
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OAuthAuthorizationJpaEntity a
               set a.status = com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.REVOKED,
                   a.revocationReason = :reason, a.revokedAt = :at
             where a.principalAccountId = :accountId and a.registeredClientId = :clientId
               and a.revokedAt is null
            """)
    int revokeByAccountIdAndClientId(@Param("accountId") long accountId,
            @Param("clientId") long clientId, @Param("reason") String reason, @Param("at") Instant at);
}
