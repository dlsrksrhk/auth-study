package com.sweet.authstudy.oauth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface OAuthSubjectJpaRepository extends JpaRepository<OAuthSubjectJpaEntity, Long> {

    Optional<OAuthSubjectJpaEntity> findByAccountId(long accountId);

    @Modifying
    @Query(value = """
            insert into oauth_subject(account_id, subject, created_at)
            values (:accountId, :subject, :createdAt)
            on conflict (account_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("accountId") long accountId,
            @Param("subject") UUID subject,
            @Param("createdAt") Instant createdAt);
}
