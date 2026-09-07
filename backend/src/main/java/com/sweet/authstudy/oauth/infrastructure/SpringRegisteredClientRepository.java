package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;

@Repository
public class SpringRegisteredClientRepository implements RegisteredClientRepository {

    private final OAuthClientRepository clients;
    private final Clock clock;
    private final OAuthSecurityProperties properties;

    public SpringRegisteredClientRepository(OAuthClientRepository clients, Clock clock,
                                            OAuthSecurityProperties properties) {
        this.clients = clients;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException(
                "OAuth clients must be changed through OAuthClientService.");
    }

    @Override
    public RegisteredClient findById(String id) {
        long internalId;
        try {
            internalId = Long.parseLong(id);
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
        return clients.findById(internalId)
                .filter(this::active)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }
        return clients.findByClientId(clientId)
                .filter(this::active)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private boolean active(OAuthClient client) {
        return client.status() == OAuthClientStatus.ACTIVE;
    }

    private RegisteredClient toRegisteredClient(OAuthClient client) {
        RegisteredClient.Builder builder = RegisteredClient.withId(client.id().toString())
                .clientId(client.clientId())
                .clientIdIssuedAt(client.createdAt())
                .clientName(client.displayName())
                .clientAuthenticationMethod(client.publicClient()
                        ? ClientAuthenticationMethod.NONE
                        : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(
                                client.trust() == OAuthClientTrust.CONSENT_REQUIRED)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(properties.authorizationCodeTtl())
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        .build());

        client.redirectUris().forEach(uri -> builder.redirectUri(uri.toString()));
        client.postLogoutRedirectUris()
                .forEach(uri -> builder.postLogoutRedirectUri(uri.toString()));
        client.scopes().forEach(builder::scope);

        if (!client.publicClient()) {
            activeSecret(client, clock.instant()).ifPresent(secret -> {
                builder.clientSecret(secret.secretHash());
                builder.clientSecretExpiresAt(secret.expiresAt());
            });
        }
        return builder.build();
    }

    private java.util.Optional<OAuthClientSecret> activeSecret(
            OAuthClient client, Instant now) {
        return client.secrets().stream()
                .filter(secret -> secret.revokedAt() == null)
                .filter(secret -> secret.expiresAt() == null || secret.expiresAt().isAfter(now))
                .max(Comparator.comparing(OAuthClientSecret::createdAt));
    }
}
