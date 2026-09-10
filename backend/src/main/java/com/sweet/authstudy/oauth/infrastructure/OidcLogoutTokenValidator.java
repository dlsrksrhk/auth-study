package com.sweet.authstudy.oauth.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class OidcLogoutTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final long CLOCK_SKEW_SECONDS = 60;
    private final Clock clock;

    public OidcLogoutTokenValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            Instant latest = clock.instant().plusSeconds(CLOCK_SKEW_SECONDS);
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant notBefore = jwt.getNotBefore();
            if (issuedAt != null && expiresAt != null && expiresAt.isAfter(issuedAt)
                    && !issuedAt.isAfter(latest)
                    && (notBefore == null || !notBefore.isAfter(latest))) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException exception) {
            // Invalid claim types must fail closed just like missing timestamps.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
