package com.sweet.authstudy.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OAuthClientTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void redirect_matching_requires_the_exact_registered_string() {
        OAuthClient client = clientWithRedirect(URI.create("https://rp.example/callback"));

        assertThat(client.allowsRedirect(URI.create("https://rp.example/callback"))).isTrue();
        assertThat(client.allowsRedirect(URI.create("https://RP.EXAMPLE/callback"))).isFalse();
        assertThat(client.allowsRedirect(URI.create("https://rp.example/callback/extra"))).isFalse();
        assertThat(client.allowsRedirect(URI.create("https://rp.example/callback?next=1"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://rp.example/callback#fragment",
            "https://user@rp.example/callback",
            "http://rp.example/callback",
            "http://localhost/callback",
            "ftp://rp.example/callback"
    })
    void creation_rejects_unsafe_redirect_uris(String redirect) {
        assertThatThrownBy(() -> clientWithRedirect(URI.create(redirect)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creation_allows_https_and_subdomain_localhost_redirects() {
        OAuthClient client = OAuthClient.create(
                41L,
                "client-id",
                "Payroll",
                true,
                Set.of(URI.create("https://rp.example/callback"),
                        URI.create("http://payroll.localhost/callback")),
                Set.of(URI.create("https://rp.example/logout")),
                Set.of("openid", "profile", "email", "hr.company", "hr.organization", "hr.roles"),
                OAuthClientTrust.CONSENT_REQUIRED,
                NOW);

        assertThat(client.redirectUris()).hasSize(2);
        assertThat(client.postLogoutRedirectUris())
                .containsExactly(URI.create("https://rp.example/logout"));
    }

    @Test
    void creation_rejects_a_scope_outside_the_allowlist() {
        assertThatThrownBy(() -> OAuthClient.create(
                41L, "client-id", "Payroll", true,
                Set.of(URI.create("https://rp.example/callback")), Set.of(),
                Set.of("openid", "payments.write"), OAuthClientTrust.CONSENT_REQUIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creation_requires_openid_scope() {
        assertThatThrownBy(() -> OAuthClient.create(
                41L, "client-id", "Payroll", true,
                Set.of(URI.create("https://rp.example/callback")), Set.of(),
                Set.of("profile", "email"), OAuthClientTrust.CONSENT_REQUIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OAuthClient clientWithRedirect(URI redirect) {
        return OAuthClient.create(
                41L,
                "client-id",
                "Payroll",
                true,
                Set.of(redirect),
                Set.of(),
                Set.of("openid", "profile"),
                OAuthClientTrust.CONSENT_REQUIRED,
                NOW);
    }
}
