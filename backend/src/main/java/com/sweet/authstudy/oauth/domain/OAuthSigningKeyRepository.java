package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface OAuthSigningKeyRepository {

    Optional<OAuthSigningKey> findActive();

    List<OAuthSigningKey> findVerificationOnlyRetiredAfter(Instant cutoff);

    OAuthSigningKey save(OAuthSigningKey key);

    OAuthSigningKey bootstrapIfAbsent(Supplier<OAuthSigningKey> candidate);

    OAuthSigningKey rotate(Supplier<OAuthSigningKey> candidate, Instant retiredAt);
}
