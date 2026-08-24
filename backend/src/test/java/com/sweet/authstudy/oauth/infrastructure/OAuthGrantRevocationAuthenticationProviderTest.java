package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

@ExtendWith(MockitoExtension.class)
class OAuthGrantRevocationAuthenticationProviderTest {
    @Mock OAuthAuthorizationRepository authorizations;
    @Mock OAuthProtocolEventService events;

    @Test
    void token_lookup_failure_becomes_server_error_without_partial_revocation() {
        RegisteredClient registeredClient = RegisteredClient.withId("1").clientId("public-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback").build();
        OAuth2ClientAuthenticationToken client = new OAuth2ClientAuthenticationToken(
                registeredClient, ClientAuthenticationMethod.NONE, null);
        OAuth2TokenRevocationAuthenticationToken request =
                new OAuth2TokenRevocationAuthenticationToken(
                        "still-usable-refresh", client, "refresh_token");
        when(authorizations.findByRefreshTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("database-timeout-secret"));
        OAuthGrantRevocationAuthenticationProvider provider =
                new OAuthGrantRevocationAuthenticationProvider(authorizations, events, Clock.systemUTC());

        assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        error -> assertThat(error.getError().getErrorCode())
                                .isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
        verify(authorizations, never()).revokeAuthorization(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
