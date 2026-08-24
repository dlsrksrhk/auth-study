package com.sweet.authstudy.oauth.infrastructure;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthClientJpaRepository extends JpaRepository<OAuthClientJpaEntity, Long> {

    Optional<OAuthClientJpaEntity> findByClientId(String clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OAuthClientJpaEntity c where c.id = :id")
    Optional<OAuthClientJpaEntity> findByIdForUpdate(@Param("id") long id);

    List<OAuthClientJpaEntity> findAllByCompanyId(long companyId);

    @Query(value = """
            select r.redirect_uri
              from oauth_client_redirect_uri r
              join oauth_client c on c.id = r.client_id
             where c.status = 'ACTIVE'
               and c.public_client = true
               and r.purpose = 'AUTHORIZATION'
             order by r.redirect_uri
            """, nativeQuery = true)
    List<String> findActivePublicAuthorizationRedirectUris();
}
