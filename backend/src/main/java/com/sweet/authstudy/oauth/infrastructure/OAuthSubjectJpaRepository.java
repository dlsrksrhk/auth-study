package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface OAuthSubjectJpaRepository extends JpaRepository<OAuthSubjectJpaEntity, Long> {

    Optional<OAuthSubjectJpaEntity> findByAccountId(long accountId);
}
