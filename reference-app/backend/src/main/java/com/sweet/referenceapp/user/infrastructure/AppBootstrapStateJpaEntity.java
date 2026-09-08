package com.sweet.referenceapp.user.infrastructure;

import com.sweet.referenceapp.user.domain.AppBootstrapState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_bootstrap_state")
public class AppBootstrapStateJpaEntity {
    @Id
    @Column(name = "singleton_key", updatable = false, nullable = false)
    private short singletonKey;
    @Column(name = "bootstrapped_user_id")
    private UUID bootstrappedUserId;
    @Column(name = "bootstrapped_at")
    private Instant bootstrappedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AppBootstrapStateJpaEntity() {}

    long version() {
        return version;
    }

    boolean completed() {
        return bootstrappedUserId != null;
    }

    void complete(UUID userId, Instant at) {
        bootstrappedUserId = userId;
        bootstrappedAt = at;
    }

    AppBootstrapState toDomain() {
        return new AppBootstrapState(singletonKey, bootstrappedUserId, bootstrappedAt, version);
    }
}
