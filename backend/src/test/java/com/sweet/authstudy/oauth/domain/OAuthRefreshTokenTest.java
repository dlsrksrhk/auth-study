package com.sweet.authstudy.oauth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRefreshTokenTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-28T00:00:00Z");
    private static final UUID FAMILY_ID = UUID.fromString("6eb2ff11-ad4d-44bf-8f77-d0f1964e6c2a");

    @Test
    void reuse_revokes_every_active_token_in_the_family_without_touching_other_families() {
        OAuthRefreshToken reused = OAuthRefreshToken.restore(
                1L, "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT,
                Instant.parse("2026-08-21T00:01:00Z"), null, 2L);
        OAuthRefreshToken successor = OAuthRefreshToken.restore(
                2L, "authorization-1", hash('b'), FAMILY_ID, ISSUED_AT, EXPIRES_AT,
                null, null, null);
        OAuthRefreshToken anotherFamily = OAuthRefreshToken.restore(
                3L, "authorization-1", hash('c'), UUID.fromString("82cb2012-1754-4fd5-8d24-ed38caf37462"),
                ISSUED_AT, EXPIRES_AT, null, null, null);
        Instant detectedAt = Instant.parse("2026-08-21T00:02:00Z");

        reused.revokeFamilyAfterReuse(List.of(reused, successor, anotherFamily), detectedAt);

        assertThat(reused.revokedAt()).isEqualTo(detectedAt);
        assertThat(successor.revokedAt()).isEqualTo(detectedAt);
        assertThat(anotherFamily.revokedAt()).isNull();
    }

    @Test
    void family_revocation_is_idempotent_and_preserves_the_first_revocation_time() {
        Instant firstRevocation = Instant.parse("2026-08-21T00:01:30Z");
        OAuthRefreshToken reused = OAuthRefreshToken.restore(
                1L, "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT,
                Instant.parse("2026-08-21T00:01:00Z"), firstRevocation, null);

        reused.revokeFamilyAfterReuse(List.of(reused), Instant.parse("2026-08-21T00:02:00Z"));

        assertThat(reused.revokedAt()).isEqualTo(firstRevocation);
    }

    @Test
    void a_token_that_has_not_been_used_cannot_report_reuse() {
        OAuthRefreshToken unused = OAuthRefreshToken.issue(
                "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT);

        assertThatThrownBy(() -> unused.revokeFamilyAfterReuse(
                List.of(unused), Instant.parse("2026-08-21T00:02:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiry_is_inclusive_at_the_exact_boundary() {
        OAuthRefreshToken token = OAuthRefreshToken.issue(
                "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT);

        assertThat(token.expiredAt(EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(token.expiredAt(EXPIRES_AT)).isTrue();
    }

    @Test
    void a_successor_from_another_family_cannot_be_linked() {
        OAuthRefreshToken current = OAuthRefreshToken.restore(
                1L, "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT,
                null, null, null);
        OAuthRefreshToken anotherFamily = OAuthRefreshToken.restore(
                2L, "authorization-1", hash('b'), UUID.fromString("82cb2012-1754-4fd5-8d24-ed38caf37462"),
                ISSUED_AT.plusSeconds(60), EXPIRES_AT, null, null, null);

        assertThatThrownBy(() -> current.markUsed(ISSUED_AT.plusSeconds(60), anotherFamily))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("family");
    }

    @Test
    void a_successor_cannot_extend_the_family_absolute_expiry() {
        OAuthRefreshToken current = OAuthRefreshToken.restore(
                1L, "authorization-1", hash('a'), FAMILY_ID, ISSUED_AT, EXPIRES_AT,
                null, null, null);
        OAuthRefreshToken extended = OAuthRefreshToken.restore(
                2L, "authorization-1", hash('b'), FAMILY_ID, ISSUED_AT.plusSeconds(60),
                EXPIRES_AT.plusSeconds(60), null, null, null);

        assertThatThrownBy(() -> current.markUsed(ISSUED_AT.plusSeconds(60), extended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute expiry");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
