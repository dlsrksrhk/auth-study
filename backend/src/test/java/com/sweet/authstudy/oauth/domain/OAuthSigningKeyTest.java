package com.sweet.authstudy.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OAuthSigningKeyTest {

    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void active_key_can_be_retired_without_exposing_mutable_private_bytes() {
        byte[] encrypted = {1, 2, 3};
        OAuthSigningKey active = OAuthSigningKey.active(
                "kid-1", "{\"kty\":\"RSA\"}", encrypted, ACTIVATED_AT);

        encrypted[0] = 9;
        byte[] returned = active.encryptedPrivateMaterial();
        returned[1] = 9;

        assertThat(active.algorithm()).isEqualTo("RS256");
        assertThat(active.status()).isEqualTo(OAuthSigningKey.Status.ACTIVE);
        assertThat(active.encryptedPrivateMaterial()).containsExactly(1, 2, 3);

        Instant retiredAt = ACTIVATED_AT.plusSeconds(30);
        OAuthSigningKey retired = active.retire(retiredAt);
        assertThat(retired.status()).isEqualTo(OAuthSigningKey.Status.VERIFICATION_ONLY);
        assertThat(retired.retiredAt()).isEqualTo(retiredAt);
        assertThat(retired.activatedAt()).isEqualTo(ACTIVATED_AT);
    }

    @Test
    void status_and_timestamp_combinations_are_fail_closed() {
        assertThatThrownBy(() -> OAuthSigningKey.restore(
                1L, "kid", "RS256", "{}", new byte[]{1},
                OAuthSigningKey.Status.ACTIVE, ACTIVATED_AT, ACTIVATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuthSigningKey.restore(
                1L, "kid", "RS256", "{}", new byte[]{1},
                OAuthSigningKey.Status.VERIFICATION_ONLY, ACTIVATED_AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuthSigningKey.restore(
                1L, "kid", "HS256", "{}", new byte[]{1},
                OAuthSigningKey.Status.ACTIVE, ACTIVATED_AT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
