package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OAuthSubject {

    private final Long id;
    private final long accountId;
    private final UUID subject;
    private final Instant createdAt;

    private OAuthSubject(Long id, long accountId, UUID subject, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.subject = Objects.requireNonNull(subject);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static OAuthSubject create(long accountId, UUID subject, Instant createdAt) {
        return new OAuthSubject(null, accountId, subject, createdAt);
    }

    public static OAuthSubject restore(Long id, long accountId, UUID subject, Instant createdAt) {
        return new OAuthSubject(id, accountId, subject, createdAt);
    }

    public Long id() {
        return id;
    }

    public long accountId() {
        return accountId;
    }

    public UUID subject() {
        return subject;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
