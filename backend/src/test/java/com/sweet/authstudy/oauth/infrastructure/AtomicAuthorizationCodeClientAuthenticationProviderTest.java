package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;

@ExtendWith(MockitoExtension.class)
class AtomicAuthorizationCodeClientAuthenticationProviderTest {

    @Mock private RegisteredClientRepository registeredClients;
    @Mock private SpringOAuth2AuthorizationService authorizations;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OAuthAuthorizationRepository domainAuthorizations;
    @Mock private OAuthProtocolEventService protocolEvents;

    @ParameterizedTest
    @ValueSource(strings = {"refresh_token", "client_credentials"})
    void custom_converter_and_provider_do_not_intercept_non_authorization_code_grants(String grantType) {
        AtomicAuthorizationCodeClientAuthenticationProvider provider =
                new AtomicAuthorizationCodeClientAuthenticationProvider(
                        registeredClients, authorizations, passwordEncoder, Clock.systemUTC());
        OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
                "client-id", ClientAuthenticationMethod.NONE, null,
                Map.of(OAuth2ParameterNames.GRANT_TYPE, grantType));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE, grantType);
        request.setParameter(OAuth2ParameterNames.CLIENT_ID, "client-id");

        assertThat(provider.authenticate(authentication)).isNull();
        assertThat(new AtomicAuthorizationCodeClientAuthenticationProvider.Converter().convert(request)).isNull();
        verifyNoInteractions(registeredClients, authorizations, passwordEncoder);
    }

    @org.junit.jupiter.api.Test
    void context_repository_failure_becomes_server_error_before_code_consumption() {
        RegisteredClient client = RegisteredClient.withId("1").clientId("public-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback").build();
        when(registeredClients.findByClientId("public-client")).thenReturn(client);
        when(domainAuthorizations.findByCodeHash(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("database-timeout-secret"));
        AtomicAuthorizationCodeClientAuthenticationProvider provider =
                new AtomicAuthorizationCodeClientAuthenticationProvider(
                        registeredClients, authorizations, passwordEncoder, Clock.systemUTC(),
                        domainAuthorizations, protocolEvents);
        OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
                "public-client", ClientAuthenticationMethod.NONE, null,
                Map.of(OAuth2ParameterNames.GRANT_TYPE, "authorization_code",
                        OAuth2ParameterNames.CODE, "still-usable-code"));

        assertThatThrownBy(() -> provider.authenticate(authentication))
                .isInstanceOf(InternalAuthenticationServiceException.class)
                .hasMessage("The authorization code exchange could not be completed.")
                .hasMessageNotContaining("database-timeout-secret");
        verify(authorizations, never()).consumeAuthorizationCode(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
