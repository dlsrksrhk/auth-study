package com.sweet.authstudy.oauth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface OAuthAccessTokenJpaRepository extends JpaRepository<OAuthAccessTokenJpaEntity, Long> {
    Optional<OAuthAccessTokenJpaEntity> findByAccessTokenHash(String accessTokenHash);

    Optional<OAuthAccessTokenJpaEntity> findFirstByAuthorizationIdOrderByIssuedAtDescIdDesc(String authorizationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OAuthAccessTokenJpaEntity t set t.revokedAt = :at "
            + "where t.authorizationId = :authorizationId and t.revokedAt is null")
    int revokeByAuthorizationId(@Param("authorizationId") String authorizationId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_access_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where principal_account_id = :accountId)
            """, nativeQuery = true)
    int revokeByAccountId(@Param("accountId") long accountId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_access_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where company_id = :companyId)
            """, nativeQuery = true)
    int revokeByCompanyId(@Param("companyId") long companyId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_access_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization where registered_client_id = :clientId)
            """, nativeQuery = true)
    int revokeByClientId(@Param("clientId") long clientId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update oauth_access_token set revoked_at = :at
             where revoked_at is null and authorization_id in
                   (select id from oauth_authorization
                     where principal_account_id = :accountId and registered_client_id = :clientId)
            """, nativeQuery = true)
    int revokeByAccountIdAndClientId(@Param("accountId") long accountId,
                                     @Param("clientId") long clientId, @Param("at") Instant at);
}
