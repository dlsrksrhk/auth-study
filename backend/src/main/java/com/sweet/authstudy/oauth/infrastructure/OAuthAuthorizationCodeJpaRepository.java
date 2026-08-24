package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthAuthorizationCodeJpaRepository extends JpaRepository<OAuthAuthorizationCodeJpaEntity, Long> {
    Optional<OAuthAuthorizationCodeJpaEntity> findByAuthorizationId(String authorizationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OAuthAuthorizationCodeJpaEntity c where c.codeHash = :hash")
    Optional<OAuthAuthorizationCodeJpaEntity> findByCodeHashForUpdate(@Param("hash") String hash);
}
