package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.UUID;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_subject")
class OAuthSubjectJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(nullable = false, updatable = false)
    private UUID subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OAuthSubjectJpaEntity() {
    }

    private OAuthSubjectJpaEntity(OAuthSubject subject) {
        this.accountId = subject.accountId();
        this.subject = subject.subject();
        this.createdAt = subject.createdAt();
    }

    static OAuthSubjectJpaEntity from(OAuthSubject subject) {
        return new OAuthSubjectJpaEntity(subject);
    }

    OAuthSubject toDomain() {
        return OAuthSubject.restore(id, accountId, subject, createdAt);
    }
}
