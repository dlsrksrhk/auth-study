package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface OAuthConsentJpaRepository extends JpaRepository<OAuthConsentJpaEntity, Long> {
    Optional<OAuthConsentJpaEntity> findByPrincipalAccountIdAndRegisteredClientId(
            long principalAccountId, long registeredClientId);
    void deleteByPrincipalAccountIdAndRegisteredClientId(long principalAccountId, long registeredClientId);
}
