package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthRefreshTokenJpaRepository extends JpaRepository<OAuthRefreshTokenJpaEntity, Long> {
    Optional<OAuthRefreshTokenJpaEntity> findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(String authorizationId);
    Optional<OAuthRefreshTokenJpaEntity> findByRefreshTokenHash(String refreshTokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OAuthRefreshTokenJpaEntity t where t.refreshTokenHash = :hash")
    Optional<OAuthRefreshTokenJpaEntity> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    @Query(value = """
            select t.id from oauth_refresh_token t
             join oauth_authorization a on a.id = t.authorization_id
             where a.principal_account_id = :accountId
             order by t.id for update of t
            """, nativeQuery = true)
    List<Long> lockByAccountId(@Param("accountId") long accountId);

    @Query(value = """
            select t.id from oauth_refresh_token t
             join oauth_authorization a on a.id = t.authorization_id
             where a.company_id = :companyId
             order by t.id for update of t
            """, nativeQuery = true)
    List<Long> lockByCompanyId(@Param("companyId") long companyId);

    @Query(value = """
            select t.id from oauth_refresh_token t
             join oauth_authorization a on a.id = t.authorization_id
             where a.registered_client_id = :clientId
             order by t.id for update of t
            """, nativeQuery = true)
    List<Long> lockByClientId(@Param("clientId") long clientId);

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
                   (select id from oauth_authorization where company_id = :companyId)
            """, nativeQuery = true)
    int revokeByCompanyId(@Param("companyId") long companyId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_refresh_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where registered_client_id = :clientId)
            """, nativeQuery = true)
    int revokeByClientId(@Param("clientId") long clientId, @Param("at") Instant at);
}
