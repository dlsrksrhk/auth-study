package com.sweet.referenceapp.user.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository {
    boolean insertIfAbsent(AppUser candidate);

    Optional<AppUser> findByIdentityForUpdate(String issuer, String subject);

    Optional<AppUser> findById(UUID id);

    Optional<AppUser> findByIdForUpdate(UUID id);

    long countActiveAdministrators();

    AppUser updateSnapshot(AppUser user);

    AppUser updateAdministration(AppUser user);

    AppUser addAdministrator(AppUser user, Instant now);
}
