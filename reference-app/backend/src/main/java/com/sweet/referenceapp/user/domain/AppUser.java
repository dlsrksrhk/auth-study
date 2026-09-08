package com.sweet.referenceapp.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AppUser(
        UUID id,
        String issuer,
        String subject,
        ExternalUserSnapshot snapshot,
        AppUserStatus status,
        Set<AppRole> roles,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        long version) {

    public AppUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must be nonnegative");
        }
        roles = Set.copyOf(roles);
        if (!roles.contains(AppRole.APP_USER)) {
            throw new IllegalArgumentException("APP_USER role is required");
        }
    }

    public static AppUser create(UUID id, String issuer, String subject,
            ExternalUserSnapshot snapshot, Instant now) {
        return new AppUser(id, issuer, subject, snapshot, AppUserStatus.ACTIVE,
                Set.of(AppRole.APP_USER), now, now, now, 0);
    }

    public AppUser replaceSnapshot(ExternalUserSnapshot next, Instant now) {
        return new AppUser(id, issuer, subject, next, status, roles,
                createdAt, now, now, version);
    }
}
