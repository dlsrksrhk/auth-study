package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public interface OAuthSigningKeyRepository {

    OAuthSigningKey requireActive();

    List<OAuthSigningKey> findVerificationOnlyRetiredAfter(Instant cutoff);

    OAuthSigningKey bootstrapIfAbsent(Supplier<OAuthSigningKey> candidate);

    OAuthSigningKey rotate(Supplier<OAuthSigningKey> candidate, Instant retiredAt);
}
