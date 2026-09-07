package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthRefreshTokenAuthenticationProviderTest {
    @Mock
    OAuthAuthorizationRepository authorizations;
    @Mock
    OAuthAuthorizationMapper mapper;
    @Mock
    OAuth2TokenGenerator<org.springframework.security.oauth2.core.OAuth2Token> tokenGenerator;
    @Mock
    OAuthSecurityProperties properties;
    @Mock
    OAuthClientRepository clients;
    @Mock
    OAuthProtocolEventService events;

    @Test
    void context_repository_failure_becomes_server_error_before_refresh_rotation() {
        RegisteredClient registeredClient = RegisteredClient.withId("1").clientId("public-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).build();
        OAuth2ClientAuthenticationToken client = new OAuth2ClientAuthenticationToken(
                registeredClient, ClientAuthenticationMethod.NONE, null);
        OAuth2RefreshTokenAuthenticationToken request = new OAuth2RefreshTokenAuthenticationToken(
                "still-usable-refresh", client, Set.of(), Map.of());
        when(authorizations.findByRefreshTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("database-deadlock-secret"));
        OAuthRefreshTokenAuthenticationProvider provider = new OAuthRefreshTokenAuthenticationProvider(
                authorizations, mapper, tokenGenerator, properties, Clock.systemUTC(), clients, events);

        assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        error -> assertThat(error.getError().getErrorCode())
                                .isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
        verify(authorizations, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
