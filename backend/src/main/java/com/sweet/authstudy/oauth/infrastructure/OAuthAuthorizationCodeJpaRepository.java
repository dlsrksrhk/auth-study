package com.sweet.authstudy.oauth.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface OAuthAuthorizationCodeJpaRepository extends JpaRepository<OAuthAuthorizationCodeJpaEntity, Long> {
    Optional<OAuthAuthorizationCodeJpaEntity> findByAuthorizationId(String authorizationId);

    Optional<OAuthAuthorizationCodeJpaEntity> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OAuthAuthorizationCodeJpaEntity c where c.codeHash = :hash")
    Optional<OAuthAuthorizationCodeJpaEntity> findByCodeHashForUpdate(@Param("hash") String hash);
}
