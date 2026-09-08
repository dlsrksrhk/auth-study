package com.sweet.referenceapp.user.domain;

import java.time.Instant;
import java.util.Optional;

public interface AppUserRepository {
    boolean insertIfAbsent(AppUser candidate);

    Optional<AppUser> findByIdentityForUpdate(String issuer, String subject);

    AppUser updateSnapshot(AppUser user);

    AppUser addAdministrator(AppUser user, Instant now);
}
