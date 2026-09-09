package com.sweet.referenceapp.user.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository {
    boolean insertIfAbsent(AppUser candidate);

    Optional<AppUser> findByIdentityForUpdate(String issuer, String subject);

    Optional<AppUser> findById(UUID id);

    AppUser updateSnapshot(AppUser user);

    AppUser addAdministrator(AppUser user, Instant now);
}
