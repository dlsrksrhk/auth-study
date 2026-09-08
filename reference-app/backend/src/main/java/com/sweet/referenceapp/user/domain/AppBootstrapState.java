package com.sweet.referenceapp.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AppBootstrapState(
        short singletonKey,
        UUID bootstrappedUserId,
        Instant bootstrappedAt,
        long version) {

    public AppBootstrapState {
        if (singletonKey != 1) {
            throw new IllegalArgumentException("singletonKey must be 1");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be nonnegative");
        }
        if ((bootstrappedUserId == null) != (bootstrappedAt == null)) {
            throw new IllegalArgumentException("Bootstrap user and time must both be present or absent");
        }
    }

    public boolean completed() {
        return bootstrappedUserId != null;
    }

    public AppBootstrapState complete(UUID userId, Instant now) {
        if (completed()) {
            throw new IllegalStateException("Bootstrap already completed");
        }
        return new AppBootstrapState(singletonKey, Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(now, "now"), version);
    }
}
