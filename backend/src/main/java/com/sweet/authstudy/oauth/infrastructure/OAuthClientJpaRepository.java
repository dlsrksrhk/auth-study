package com.sweet.authstudy.oauth.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface OAuthClientJpaRepository extends JpaRepository<OAuthClientJpaEntity, Long> {

    Optional<OAuthClientJpaEntity> findByClientId(String clientId);

    List<OAuthClientJpaEntity> findAllByCompanyId(long companyId);
}
