package com.sweet.authstudy.oauth.domain;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void ownership_rejects_a_subject_from_another_account() {
        OAuthClient client = persistedClient(10L, 20L);
        OAuthSubject subject = OAuthSubject.restore(30L, 40L, UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> OAuthAuthorization.Ownership.verified(client, subject, 41L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void ownership_rejects_a_client_from_another_company() {
        OAuthClient client = persistedClient(10L, 20L);
        OAuthSubject subject = OAuthSubject.restore(30L, 40L, UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> OAuthAuthorization.Ownership.verified(client, subject, 40L, 11L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client");
    }

    private OAuthClient persistedClient(long companyId, long id) {
        return OAuthClient.restore(
                id, companyId, "opaque-client", "Client", OAuthClientStatus.ACTIVE,
                OAuthClientTrust.CONSENT_REQUIRED, true, 0,
                Set.of(URI.create("https://rp.example/callback")), Set.of(), Set.of("openid"),
                Set.of(), NOW, NOW);
    }
}
