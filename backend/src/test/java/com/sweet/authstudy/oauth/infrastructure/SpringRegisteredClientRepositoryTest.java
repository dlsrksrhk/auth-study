package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringRegisteredClientRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final URI CALLBACK = URI.create("https://rp.example/callback?source=idp");
    private static final URI LOCAL_CALLBACK = URI.create("http://rp.localhost/callback");
    private static final URI LOGOUT = URI.create("https://rp.example/logout");

    @Mock
    private OAuthClientRepository clients;

    private SpringRegisteredClientRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SpringRegisteredClientRepository(
                clients, Clock.fixed(NOW, ZoneOffset.UTC), properties());
    }

    @Test
    void maps_public_client_without_a_secret_and_requires_pkce() {
        when(clients.findByClientId("public-id")).thenReturn(Optional.of(publicClient()));

        RegisteredClient registered = repository.findByClientId("public-id");

        assertThat(registered.getId()).isEqualTo("11");
        assertThat(registered.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(registered.getClientSecret()).isNull();
        assertThat(registered.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(registered.getRedirectUris())
                .containsExactlyInAnyOrder(CALLBACK.toString(), LOCAL_CALLBACK.toString());
        assertThat(registered.getPostLogoutRedirectUris()).containsExactly(LOGOUT.toString());
        assertThat(registered.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(registered.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(registered.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(registered.getTokenSettings().getAuthorizationCodeTimeToLive())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(registered.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(registered.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void maps_confidential_client_hash_and_trusted_consent_policy() {
        OAuthClient client = confidentialClient(OAuthClientStatus.ACTIVE);
        when(clients.findById(22L)).thenReturn(Optional.of(client));

        RegisteredClient registered = repository.findById("22");

        assertThat(registered.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registered.getClientSecret()).isEqualTo(
                client.secrets().iterator().next().secretHash());
        assertThat(registered.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(registered.getRedirectUris()).containsExactly(CALLBACK.toString());
        assertThat(registered.getPostLogoutRedirectUris()).containsExactly(LOGOUT.toString());
        assertThat(registered.getScopes()).containsExactlyInAnyOrder("openid", "email", "hr.roles");
        assertThat(registered.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(registered.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    void inactive_clients_are_invisible_by_internal_and_external_id() {
        OAuthClient disabled = confidentialClient(OAuthClientStatus.DISABLED);
        when(clients.findById(22L)).thenReturn(Optional.of(disabled));
        when(clients.findByClientId("confidential-id")).thenReturn(Optional.of(disabled));

        assertThat(repository.findById("22")).isNull();
        assertThat(repository.findByClientId("confidential-id")).isNull();
    }

    @Test
    void malformed_internal_id_is_not_a_registered_client() {
        assertThat(repository.findById("not-a-database-id")).isNull();
    }

    @Test
    void spring_repository_cannot_bypass_the_client_application_service() {
        RegisteredClient registered = org.springframework.security.oauth2.server.authorization.client.RegisteredClient
                .withId("11")
                .clientId("public-id")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CALLBACK.toString())
                .scope("openid")
                .build();

        assertThatThrownBy(() -> repository.save(registered))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("OAuthClientService");
    }

    @Test
    void oauth_secret_encoder_only_verifies_existing_bcrypt_hashes() {
        OAuthClientSecretPasswordEncoder encoder = new OAuthClientSecretPasswordEncoder();
        String hash = new BCryptPasswordEncoder().encode("correct-secret");

        assertThat(encoder.matches("correct-secret", hash)).isTrue();
        assertThat(encoder.matches("wrong-secret", hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
        assertThatThrownBy(() -> encoder.encode("must-not-be-written-here"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private OAuthClient publicClient() {
        return OAuthClient.restore(
                11L, 101L, "public-id", "Public RP", OAuthClientStatus.ACTIVE,
                OAuthClientTrust.CONSENT_REQUIRED, true, 3,
                Set.of(CALLBACK, LOCAL_CALLBACK), Set.of(LOGOUT), Set.of("openid", "profile"),
                Set.of(), NOW.minusSeconds(300), NOW.minusSeconds(60));
    }

    private OAuthClient confidentialClient(OAuthClientStatus status) {
        String hash = new BCryptPasswordEncoder().encode("correct-secret");
        return OAuthClient.restore(
                22L, 202L, "confidential-id", "Confidential RP", status,
                OAuthClientTrust.TRUSTED_FIRST_PARTY, false, 5,
                Set.of(CALLBACK), Set.of(LOGOUT), Set.of("openid", "email", "hr.roles"),
                Set.of(OAuthClientSecret.restore(
                        7L, hash, "cret", NOW.minusSeconds(120), null, null, 0)),
                NOW.minusSeconds(600), NOW.minusSeconds(120));
    }

    private OAuthSecurityProperties properties() {
        return new OAuthSecurityProperties(
                URI.create("http://idp.localhost:8080"), Duration.ofSeconds(60),
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofDays(7),
                Duration.ofMinutes(30), Duration.ofHours(8), "IDP_AUTH_SESSION",
                "auth-study-userinfo");
    }
}
