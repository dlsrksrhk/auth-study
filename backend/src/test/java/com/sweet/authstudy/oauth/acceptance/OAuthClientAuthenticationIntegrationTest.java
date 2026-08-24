package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class,
        OAuthClientAuthenticationIntegrationTest.ProtocolTestConfiguration.class})
@ActiveProfiles("test")
class OAuthClientAuthenticationIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://rp.example/callback");
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OAuthClientRepository clients;

    @BeforeEach
    void registerClients() {
        when(clients.findByClientId("confidential-id"))
                .thenReturn(Optional.of(confidentialClient()));
        when(clients.findByClientId("public-id"))
                .thenReturn(Optional.of(publicClient()));
    }

    @Test
    void wrong_confidential_secret_returns_invalid_client() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Origin", ISSUER)
                        .header("Authorization", basic("confidential-id", "wrong-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-real-code")
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void correct_confidential_secret_advances_to_grant_validation() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Origin", ISSUER)
                        .header("Authorization", basic("confidential-id", "correct-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-real-code")
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void public_client_presenting_a_secret_returns_invalid_client() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Origin", ISSUER)
                        .header("Authorization", basic("public-id", "invented-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-real-code")
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void unknown_authentication_infrastructure_failure_is_stable_server_error_without_oracle() throws Exception {
        when(clients.findByClientId("fault-client"))
                .thenThrow(new IllegalStateException("database-timeout internal-secret stack"));

        var result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("fault-client", "presented-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-real-code"))
                .andExpect(status().isBadRequest()).andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.fieldNames()).toIterable().containsExactly("error");
        assertThat(body.path("error").asText()).isEqualTo("server_error");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("database-timeout", "internal-secret", "stack", "presented-secret");
    }

    @Test
    void authorization_request_without_pkce_returns_invalid_request() throws Exception {
        var result = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "public-id")
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", "opaque-state"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        assertThat(location).startsWith(CALLBACK.toString());
        String decodedQuery = URLDecoder.decode(
                URI.create(location).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(decodedQuery).contains("error=invalid_request");
        assertThat(decodedQuery).contains("error_description=OAuth 2.0 Parameter: code_challenge");
        assertThat(decodedQuery).contains("state=opaque-state");
    }

    private String basic(String clientId, String secret) {
        String credentials = Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + credentials;
    }

    private OAuthClient confidentialClient() {
        String hash = new BCryptPasswordEncoder().encode("correct-secret");
        return OAuthClient.restore(
                22L, 202L, "confidential-id", "Confidential RP", OAuthClientStatus.ACTIVE,
                OAuthClientTrust.CONSENT_REQUIRED, false, 0,
                Set.of(CALLBACK), Set.of(), Set.of("openid"),
                Set.of(OAuthClientSecret.restore(
                        7L, hash, "cret", NOW.minusSeconds(60), null, null, 0)),
                NOW.minusSeconds(120), NOW.minusSeconds(60));
    }

    private OAuthClient publicClient() {
        return OAuthClient.restore(
                11L, 101L, "public-id", "Public RP", OAuthClientStatus.ACTIVE,
                OAuthClientTrust.CONSENT_REQUIRED, true, 0,
                Set.of(CALLBACK), Set.of(), Set.of("openid"), Set.of(), NOW, NOW);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtocolTestConfiguration {

        @Bean
        OAuth2AuthorizationService oauth2AuthorizationService() {
            return new InMemoryOAuth2AuthorizationService();
        }

        @Bean
        OAuth2AuthorizationConsentService oauth2AuthorizationConsentService() {
            return new InMemoryOAuth2AuthorizationConsentService();
        }

    }
}
