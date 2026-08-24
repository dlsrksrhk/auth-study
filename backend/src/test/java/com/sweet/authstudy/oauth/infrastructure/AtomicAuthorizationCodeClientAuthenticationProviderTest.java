package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@ExtendWith(MockitoExtension.class)
class AtomicAuthorizationCodeClientAuthenticationProviderTest {

    @Mock private RegisteredClientRepository registeredClients;
    @Mock private SpringOAuth2AuthorizationService authorizations;
    @Mock private PasswordEncoder passwordEncoder;

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
}
