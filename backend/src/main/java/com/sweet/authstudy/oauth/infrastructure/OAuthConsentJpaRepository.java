package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthConsentJpaRepository extends JpaRepository<OAuthConsentJpaEntity, Long> {
    Optional<OAuthConsentJpaEntity> findByPrincipalAccountIdAndRegisteredClientId(
            long principalAccountId, long registeredClientId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OAuthConsentJpaEntity c where c.principalAccountId = :accountId and c.registeredClientId = :clientId")
    Optional<OAuthConsentJpaEntity> findForUpdate(
            @Param("accountId") long accountId, @Param("clientId") long registeredClientId);

    @Query(value = "select pg_advisory_xact_lock(hashtextextended(concat(:accountId, ':', :clientId), 0))",
            nativeQuery = true)
    Object lockDecision(@Param("accountId") long accountId, @Param("clientId") long registeredClientId);
    void deleteByPrincipalAccountIdAndRegisteredClientId(long principalAccountId, long registeredClientId);
}
