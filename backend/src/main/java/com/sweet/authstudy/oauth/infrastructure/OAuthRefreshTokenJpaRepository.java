package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthRefreshTokenJpaRepository extends JpaRepository<OAuthRefreshTokenJpaEntity, Long> {
    Optional<OAuthRefreshTokenJpaEntity> findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(String authorizationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OAuthRefreshTokenJpaEntity t where t.refreshTokenHash = :hash")
    Optional<OAuthRefreshTokenJpaEntity> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OAuthRefreshTokenJpaEntity t set t.revokedAt = :at where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_refresh_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where principal_account_id = :accountId)
            """, nativeQuery = true)
    int revokeByAccountId(@Param("accountId") long accountId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_refresh_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where registered_client_id = :clientId)
            """, nativeQuery = true)
    int revokeByClientId(@Param("clientId") long clientId, @Param("at") Instant at);
}
