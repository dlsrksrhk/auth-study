package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AppUserView(
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

    public static AppUserView from(AppUser user) {
        return new AppUserView(user.id(), user.issuer(), user.subject(), user.snapshot(),
                user.status(), user.roles(), user.createdAt(), user.updatedAt(),
                user.lastLoginAt(), user.version());
    }
}
