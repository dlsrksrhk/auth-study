package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class OAuthConsent {

    private final Long id;
    private final long principalAccountId;
    private final long registeredClientId;
    private final Set<String> scopes;
    private final Instant createdAt;
    private Instant updatedAt;

    private OAuthConsent(Long id, long principalAccountId, long registeredClientId, Set<String> scopes,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.principalAccountId = principalAccountId;
        this.registeredClientId = registeredClientId;
        this.scopes = normalizeScopes(scopes);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static OAuthConsent create(long principalAccountId, long registeredClientId,
            Set<String> scopes, Instant now) {
        return new OAuthConsent(null, principalAccountId, registeredClientId, scopes, now, now);
    }

    public static OAuthConsent restore(Long id, long principalAccountId, long registeredClientId,
            Set<String> scopes, Instant createdAt, Instant updatedAt) {
        return new OAuthConsent(id, principalAccountId, registeredClientId, scopes, createdAt, updatedAt);
    }

    public void grant(Set<String> grantedScopes, Instant now) {
        scopes.addAll(normalizeScopes(grantedScopes));
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void replaceScopes(Set<String> suppliedScopes, Instant now) {
        scopes.clear();
        scopes.addAll(normalizeScopes(suppliedScopes));
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean covers(Set<String> requestedScopes) {
        return scopes.containsAll(normalizeScopes(requestedScopes));
    }

    public Long id() { return id; }
    public long principalAccountId() { return principalAccountId; }
    public long registeredClientId() { return registeredClientId; }
    public Set<String> scopes() { return Set.copyOf(scopes); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    static Set<String> normalizeScopes(Set<String> values) {
        Objects.requireNonNull(values, "scopes");
        return values.stream().map(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Scope must not be blank.");
            }
            String normalized = value.trim();
            if (normalized.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Scope must be a single token.");
            }
            return normalized;
        }).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
