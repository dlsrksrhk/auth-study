package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppExternalSnapshotService {
    private final AppUserRepository repository;
    private final Clock clock;

    public AppExternalSnapshotService(AppUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public AppUserView refresh(UUID userId, ExternalIdentityProfile profile) {
        var current = repository.findByIdentityForUpdate(
                        profile.issuer().toString(), profile.subject())
                .orElseThrow(() -> new IllegalStateException("Local user unavailable"));
        if (!current.id().equals(userId) || current.status() != AppUserStatus.ACTIVE) {
            throw new IllegalStateException("Local user unavailable");
        }
        return AppUserView.from(repository.updateSnapshot(
                current.refreshSnapshot(profile.snapshot(), clock.instant())));
    }
}
