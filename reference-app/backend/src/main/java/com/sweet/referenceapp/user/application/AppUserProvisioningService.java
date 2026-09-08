package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserProvisioningService {
    private final AppUserRepository repository;
    private final Clock clock;

    public AppUserProvisioningService(AppUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppUserView provision(ExternalIdentityProfile profile) {
        Objects.requireNonNull(profile, "profile");
        var candidate = AppUser.create(UUID.randomUUID(), profile.issuer().toString(),
                profile.subject(), profile.snapshot(), clock.instant());
        boolean inserted = repository.insertIfAbsent(candidate);
        var current = repository.findByIdentityForUpdate(candidate.issuer(), candidate.subject())
                .orElseThrow(() -> new IllegalStateException("Provisioned identity missing"));
        if (inserted) {
            return AppUserView.from(current);
        }
        return AppUserView.from(repository.updateSnapshot(
                current.replaceSnapshot(profile.snapshot(), clock.instant())));
    }
}
