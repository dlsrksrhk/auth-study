package com.sweet.authstudy.oauth.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.presentation.IdpLoginController;
import com.sweet.authstudy.oauth.presentation.IdpSessionAuthentication;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OAuthProtocolSecurityAcceptanceTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final String PUBLIC_ORIGIN = "http://public-rp.localhost:3100";
    private static final String CSP = "default-src 'self'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'; object-src 'none'";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    @Qualifier("oauthJwtEncoder")
    private JwtEncoder jwtEncoder;
    @Autowired
    @Qualifier("oauthJwtDecoder")
    private JwtDecoder jwtDecoder;

    @AfterEach
    void removeLegacyRedirectFixtures() {
        jdbcClient.sql("""
                delete from oauth_client
                 where id in (
                       select client_id from oauth_client_redirect_uri
                        where redirect_uri in ('https:legacy-hostless',
                                               'https://legacy.localhost:bad/callback'))
                """).update();
    }

    @Test
    void idp_browser_pages_and_authorization_redirects_send_no_referrer_frame_denial_and_self_only_csp()
            throws Exception {
        assertBrowserHeaders(mockMvc.perform(get("/idp/login"))
                .andExpect(status().isOk()).andReturn());

        MvcResult authorizationRedirect = mockMvc.perform(get("/oauth2/authorize"))
                .andExpect(status().isBadRequest()).andReturn();
        assertBrowserHeaders(authorizationRedirect);

        assertBrowserHeaders(mockMvc.perform(get("/idp/error"))
                .andExpect(status().isOk()).andReturn());
        ProtocolFixture fixture = protocolFixture();
        MockHttpSession passwordSession = idpSession(fixture);
        passwordSession.setAttribute(IdpLoginController.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, true);
        MvcResult passwordPage = mockMvc.perform(get("/idp/password").session(passwordSession))
                .andExpect(status().isOk()).andReturn();
        assertBrowserHeaders(passwordPage);
        MvcResult passwordFailure = mockMvc.perform(post("/idp/password").session(passwordSession).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", hidden(passwordPage.getResponse().getContentAsString(), "flowId"))
                        .param("currentPassword", "WrongPassword1234!")
                        .param("newPassword", "DifferentPassword1234!"))
                .andExpect(status().isOk()).andReturn();
        assertBrowserHeaders(passwordFailure);
        MvcResult passwordSuccess = mockMvc.perform(post("/idp/password").session(passwordSession).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", hidden(passwordFailure.getResponse().getContentAsString(), "flowId"))
                        .param("currentPassword", "ProtocolPassword1234!")
                        .param("newPassword", "DifferentPassword1234!"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(passwordSuccess);
    }

    @Test
    void protocol_cors_echoes_only_an_active_public_clients_exact_registered_origin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String publicOrigin = "http://public-" + suffix + ".localhost:3100";
        String inactiveOrigin = "http://inactive-" + suffix + ".localhost:3100";
        String confidentialOrigin = "http://confidential-" + suffix + ".localhost:3100";
        registerClient(publicOrigin + "/callback", true, true);
        registerClient(inactiveOrigin + "/callback", false, true);
        registerClient(confidentialOrigin + "/callback", true, false);
        registerClient("https:legacy-hostless", true, true);
        registerClient("https://legacy.localhost:bad/callback", true, true);

        for (CorsCase allowed : List.of(
                new CorsCase("/.well-known/openid-configuration", "GET"),
                new CorsCase("/oauth2/jwks", "GET"),
                new CorsCase("/oauth2/token", "POST"))) {
            mockMvc.perform(options(allowed.path())
                            .header("Origin", publicOrigin)
                            .header("Access-Control-Request-Method", allowed.method()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", publicOrigin))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }

        MvcResult discovery = mockMvc.perform(get("/.well-known/openid-configuration")
                        .header("Origin", publicOrigin))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", publicOrigin)).andReturn();
        JsonNode metadata = objectMapper.readTree(discovery.getResponse().getContentAsByteArray());
        assertThat(metadata.path("issuer").asText()).isEqualTo(ISSUER);
        assertThat(metadata.path("authorization_endpoint").asText()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(metadata.path("token_endpoint").asText()).isEqualTo(ISSUER + "/oauth2/token");
        assertThat(metadata.path("jwks_uri").asText()).isEqualTo(ISSUER + "/oauth2/jwks");
        assertThat(metadata.path("userinfo_endpoint").asText()).isEqualTo(ISSUER + "/userinfo");
        assertThat(metadata.path("end_session_endpoint").asText()).isEqualTo(ISSUER + "/connect/logout");
        mockMvc.perform(get("/oauth2/jwks").header("Origin", publicOrigin))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", publicOrigin));
        mockMvc.perform(post("/oauth2/token").header("Origin", publicOrigin)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "unknown")
                        .param("code", "invalid")
                        .param("redirect_uri", publicOrigin + "/callback")
                        .param("code_verifier", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().string("Access-Control-Allow-Origin", publicOrigin));

        for (String deniedOrigin : List.of(inactiveOrigin, confidentialOrigin,
                "http://unregistered.localhost:3100", "not a valid origin", "null")) {
            mockMvc.perform(options("/oauth2/token")
                            .header("Origin", deniedOrigin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
        mockMvc.perform(get("/oauth2/authorize").header("Origin", publicOrigin))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", "unknown")
                        .param("code", "invalid"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(post("/oauth2/token").header("Origin", ISSUER)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "unknown")
                        .param("code", "invalid"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void protocol_cors_normalizes_bracketed_ipv6_origins_without_double_brackets() throws Exception {
        registerClient("https://[::1]:8443/callback", true, true);
        registerClient("https://[::1]:443/callback", true, true);

        for (String origin : List.of("https://[::1]:8443", "https://[::1]")) {
            mockMvc.perform(options("/oauth2/token")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void task_12_protocol_event_boundary_is_present() {
        assertThatCode(() -> Class.forName(
                "com.sweet.authstudy.oauth.application.OAuthProtocolEventService"))
                .doesNotThrowAnyException();
    }

    @Test
    void protocol_event_metadata_accepts_only_typed_allowlisted_values_and_rejects_sensitive_keys_or_values() {
        assertThatThrownBy(() -> protocolMetadata(Map.of("authorization_code", "raw-code-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
        assertThatThrownBy(() -> protocolMetadata(Map.of("reason", "raw-secret-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");

        Object metadata = protocolMetadata(Map.of(
                "endpoint", "TOKEN",
                "grant_type", "AUTHORIZATION_CODE",
                "scopes", List.of("openid", "profile"),
                "public_client", true));
        assertThat(metadata).extracting("endpoint", "grantType", "scopes", "publicClient")
                .containsExactly(
                        enumValue("com.sweet.authstudy.oauth.domain.OAuthProtocolEvent$Endpoint", "TOKEN"),
                        enumValue("com.sweet.authstudy.oauth.domain.OAuthProtocolEvent$GrantType",
                                "AUTHORIZATION_CODE"),
                        java.util.Set.of("openid", "profile"),
                        true);
    }

    @Test
    void protocol_event_service_persists_only_the_sanitized_typed_event_contract() throws Exception {
        OAuthProtocolEvent event = OAuthProtocolEvent.create(
                Instant.parse("2026-08-25T00:00:00Z"), "trace-safe-123",
                OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_EXCHANGED,
                OAuthProtocolEvent.Outcome.SUCCESS,
                "public-rp", UUID.fromString("00000000-0000-0000-0000-000000000123"),
                null, null, null, null,
                OAuthProtocolEvent.Metadata.from(Map.of(
                        "endpoint", "TOKEN",
                        "grant_type", "AUTHORIZATION_CODE",
                        "scopes", List.of("openid"),
                        "public_client", true)));

        recordProtocolEvent(event);

        Map<String, Object> row = jdbcClient.sql("""
                select correlation_id, event_type, outcome, client_id, subject::text, metadata::text
                from oauth_protocol_event where correlation_id = 'trace-safe-123'
                """).query().singleRow();
        assertThat(row).containsEntry("correlation_id", "trace-safe-123")
                .containsEntry("event_type", "AUTHORIZATION_CODE_EXCHANGED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("client_id", "public-rp")
                .containsEntry("subject", "00000000-0000-0000-0000-000000000123");
        JsonNode metadata = objectMapper.readTree(row.get("metadata").toString());
        assertThat(metadata.path("endpoint").asText()).isEqualTo("TOKEN");
        assertThat(metadata.path("grant_type").asText()).isEqualTo("AUTHORIZATION_CODE");
        assertThat(metadata.toString()).doesNotContain(
                "code_verifier", "access_token", "refresh_token", "secret", "password");
    }

    @Test
    void authorization_errors_stay_local_until_client_and_redirect_are_fully_validated() throws Exception {
        MvcResult unsafe = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "unknown-client")
                        .queryParam("redirect_uri", "https://attacker.example/callback")
                        .queryParam("scope", "openid")
                        .queryParam("state", "safe-state")
                        .queryParam("code_challenge", "a".repeat(43))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/idp/error")).andReturn();
        assertBrowserHeaders(unsafe);
        assertThat(unsafe.getResponse().getContentAsString())
                .doesNotContain("attacker.example", "safe-state", "unknown-client");

        String redirect = "http://safe-error.localhost:3100/callback";
        String clientId = registerPublicClient(redirect, true);
        MvcResult safe = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", redirect)
                        .queryParam("scope", "openid")
                        .queryParam("state", "validated-state"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(safe);
        URI location = URI.create(safe.getResponse().getHeader("Location"));
        assertThat(location.getScheme() + "://" + location.getAuthority() + location.getPath())
                .isEqualTo(redirect);
        assertThat(UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst("state"))
                .isEqualTo("validated-state");
        assertThat(location.toString()).doesNotContain("attacker.example", "%0d", "%0a");

        List<Map<String, Object>> failures = jdbcClient.sql("""
                select outcome, client_id, error_code, metadata::text
                  from oauth_protocol_event
                 where event_type = 'AUTHORIZATION_REQUEST_VALIDATED' and outcome = 'FAILURE'
                 order by id desc limit 2
                """).query().listOfRows();
        assertThat(failures).hasSize(2).allSatisfy(event -> assertThat(event)
                .containsEntry("outcome", "FAILURE").containsEntry("error_code", "invalid_request"));
        assertThat(failures).anySatisfy(event -> {
            assertThat(event.get("client_id")).isEqualTo(clientId);
            assertThat(event.get("metadata").toString()).contains("redirect_validated", "true");
        }).anySatisfy(event -> {
            assertThat(event.get("client_id")).isNull();
            assertThat(event.get("metadata").toString()).contains("redirect_validated", "false");
        });
    }

    @Test
    void token_failures_use_stable_rfc_json_without_internal_messages_or_stack_traces() throws Exception {
        String clientId = registerPublicClient(PUBLIC_ORIGIN + "/token-error", true);

        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", "not-a-real-code")
                        .param("redirect_uri", PUBLIC_ORIGIN + "/token-error")
                        .param("code_verifier", "v".repeat(43)))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.fieldNames()).toIterable().containsExactly("error");
        assertThat(error.path("error").asText()).isEqualTo("invalid_grant");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception", "stack", "client_secret", "code_verifier", "not-a-real-code");
        Map<String, Object> event = jdbcClient.sql("""
                select outcome, error_code, metadata::text from oauth_protocol_event
                 where event_type = 'AUTHORIZATION_CODE_EXCHANGED' and outcome = 'FAILURE'
                 order by id desc limit 1
                """).query().singleRow();
        assertThat(event).containsEntry("outcome", "FAILURE").containsEntry("error_code", "invalid_grant");
        assertThat(event.get("metadata").toString()).doesNotContain("not-a-real-code", "code_verifier");
    }

    @Test
    void real_authorization_code_exchange_records_correlated_sanitized_success_events() throws Exception {
        ProtocolFixture fixture = protocolFixture();
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String state = "state-" + UUID.randomUUID();
        MvcResult authorization = mockMvc.perform(get("/oauth2/authorize")
                        .session(idpSession(fixture))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", fixture.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", state)
                        .queryParam("nonce", "nonce-safe")
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(authorization.getResponse().getHeader("Location"));
        String code = UriComponentsBuilder.fromUri(callback).build().getQueryParams().getFirst("code");

        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", code)
                        .param("redirect_uri", fixture.redirectUri())
                        .param("code_verifier", verifier))
                .andExpect(status().isOk());

        List<Map<String, Object>> events = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       authorization_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId
                 order by id
                """).param("clientId", fixture.clientId()).query().listOfRows();
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("AUTHORIZATION_REQUEST_VALIDATED", "AUTHORIZATION_CODE_ISSUED",
                        "AUTHORIZATION_CODE_EXCHANGED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event).containsEntry("outcome", "SUCCESS")
                    .containsEntry("client_id", fixture.clientId())
                    .containsEntry("company_id", fixture.companyId());
            assertThat(event.get("error_code")).isNull();
            assertThat(event.get("metadata").toString()).doesNotContain(
                    code, verifier, "access_token", "refresh_token", "secret", "password");
        });
        assertThat(events.getFirst().get("subject")).isNull();
        assertThat(events.getFirst().get("authorization_id")).isNull();
        assertThat(events.subList(1, events.size())).allSatisfy(event -> {
            assertThat(event).containsEntry("subject", fixture.subject().toString())
                    .containsEntry("account_id", fixture.accountId());
            assertThat(event.get("authorization_id")).isNotNull();
        });
    }

    @Test
    void actual_login_failure_and_success_emit_generic_sanitized_events_and_secure_responses() throws Exception {
        ProtocolFixture fixture = protocolFixture();
        MockHttpSession session = new MockHttpSession();
        MvcResult start = mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", fixture.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "login-state")
                        .queryParam("nonce", "login-nonce")
                        .queryParam("code_challenge", "a".repeat(43))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(start);

        MvcResult page = mockMvc.perform(get("/idp/login").session(session))
                .andExpect(status().isOk()).andReturn();
        assertBrowserHeaders(page);
        String flowId = hidden(page.getResponse().getContentAsString(), "flowId");
        MvcResult failed = mockMvc.perform(post("/idp/login").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", flowId)
                        .param("email", fixture.email())
                        .param("password", "WrongPassword1234!"))
                .andExpect(status().isOk()).andReturn();
        assertBrowserHeaders(failed);
        String retryFlow = hidden(failed.getResponse().getContentAsString(), "flowId");

        MvcResult succeeded = mockMvc.perform(post("/idp/login").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", retryFlow)
                        .param("email", fixture.email())
                        .param("password", "ProtocolPassword1234!"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(succeeded);

        List<Map<String, Object>> loginEvents = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId
                   and event_type in ('LOGIN_REQUIRED', 'LOGIN_FAILED', 'LOGIN_SUCCEEDED')
                 order by id
                """).param("clientId", fixture.clientId()).query().listOfRows();
        assertThat(loginEvents).extracting(row -> row.get("event_type"))
                .containsExactly("LOGIN_REQUIRED", "LOGIN_FAILED", "LOGIN_SUCCEEDED");
        assertThat(loginEvents.get(1)).containsEntry("outcome", "FAILURE")
                .containsEntry("error_code", "login_failed")
                .containsEntry("company_id", fixture.companyId());
        assertThat(loginEvents.getLast()).containsEntry("outcome", "SUCCESS")
                .containsEntry("subject", fixture.subject().toString())
                .containsEntry("account_id", fixture.accountId())
                .containsEntry("company_id", fixture.companyId());
        assertThat(loginEvents.toString()).doesNotContain(
                fixture.email(), "WrongPassword1234!", "ProtocolPassword1234!");
    }

    @Test
    void real_refresh_rotation_and_reuse_emit_success_and_failure_without_a_protocol_oracle() throws Exception {
        ProtocolFixture fixture = protocolFixture("refresh-secret-" + UUID.randomUUID());
        TokenSet tokens = issueTokens(fixture);

        MvcResult rotated = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken()))
                .andExpect(status().isOk()).andReturn();
        JsonNode rotatedBody = objectMapper.readTree(rotated.getResponse().getContentAsByteArray());
        assertThat(rotatedBody.path("refresh_token").asText()).isNotBlank()
                .isNotEqualTo(tokens.refreshToken());

        MvcResult replay = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken()))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsByteArray())
                .path("error").asText()).isEqualTo("invalid_grant");

        List<Map<String, Object>> events = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       authorization_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId
                   and event_type in ('REFRESH_ROTATED', 'REFRESH_REUSE_DETECTED')
                 order by id
                """).param("clientId", fixture.clientId()).query().listOfRows();
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("REFRESH_ROTATED", "REFRESH_REUSE_DETECTED");
        assertThat(events.getFirst()).containsEntry("outcome", "SUCCESS");
        assertThat(events.getLast()).containsEntry("outcome", "FAILURE")
                .containsEntry("error_code", "invalid_grant");
        assertThat(events).allSatisfy(event -> {
            assertThat(event).containsEntry("subject", fixture.subject().toString())
                    .containsEntry("account_id", fixture.accountId())
                    .containsEntry("company_id", fixture.companyId());
            assertThat(event.get("authorization_id")).isNotNull();
            assertThat(event.get("metadata").toString()).doesNotContain(tokens.refreshToken());
        });
    }

    @Test
    void refresh_scope_escalation_returns_invalid_scope_without_consuming_the_refresh_token()
            throws Exception {
        ProtocolFixture fixture = protocolFixture("scope-secret-" + UUID.randomUUID());
        TokenSet tokens = issueTokens(fixture);

        MvcResult excessive = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken())
                        .param("scope", "openid email"))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode error = objectMapper.readTree(excessive.getResponse().getContentAsByteArray());
        assertThat(error.fieldNames()).toIterable().containsExactly("error");
        assertThat(error.path("error").asText()).isEqualTo("invalid_scope");

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken()))
                .andExpect(status().isOk());
        Map<String, Object> event = jdbcClient.sql("""
                select event_type, outcome, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId and event_type = 'REFRESH_ROTATED'
                   and outcome = 'FAILURE'
                 order by id desc limit 1
                """).param("clientId", fixture.clientId()).query().singleRow();
        assertThat(event).containsEntry("error_code", "invalid_scope");
        assertThat(event.get("metadata").toString()).contains("INVALID_SCOPE")
                .doesNotContain(tokens.refreshToken());
    }

    @Test
    void token_endpoint_preserves_standard_errors_and_challenges_only_failed_basic_authentication()
            throws Exception {
        ProtocolFixture confidential = protocolFixture("rfc-errors-secret-" + UUID.randomUUID());
        ProtocolFixture publicClient = protocolFixture();

        MvcResult unsupported = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(confidential.clientId(), confidential.rawSecret()))
                        .param("grant_type", "urn:example:unsupported"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
        assertExactError(unsupported, "unsupported_grant_type");

        MvcResult unauthorized = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(confidential.clientId(), confidential.rawSecret()))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
        assertExactError(unauthorized, "unauthorized_client");

        MvcResult publicFailure = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(publicClient.clientId(), "attacker-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic")).andReturn();
        assertExactError(publicFailure, "invalid_client");

        MvcResult nonBasicFailure = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", "unregistered-public-client")
                        .param("code", "not-a-code"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
        assertExactError(nonBasicFailure, "invalid_client");

        MvcResult basicFailure = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(confidential.clientId(), "wrong-basic-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", "not-a-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic")).andReturn();
        assertExactError(basicFailure, "invalid_client");
    }

    @Test
    void authorization_code_replay_returns_the_same_generic_error_and_records_only_safe_classification()
            throws Exception {
        ProtocolFixture fixture = protocolFixture();
        TokenSet tokens = issueTokens(fixture);

        MvcResult replay = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", tokens.code())
                        .param("redirect_uri", fixture.redirectUri())
                        .param("code_verifier", tokens.verifier()))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode body = objectMapper.readTree(replay.getResponse().getContentAsByteArray());
        assertThat(body.fieldNames()).toIterable().containsExactly("error");
        assertThat(body.path("error").asText()).isEqualTo("invalid_grant");

        Map<String, Object> event = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       authorization_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId and event_type = 'AUTHORIZATION_CODE_REPLAY_REJECTED'
                 order by id desc limit 1
                """).param("clientId", fixture.clientId()).query().singleRow();
        assertThat(event).containsEntry("outcome", "FAILURE")
                .containsEntry("subject", fixture.subject().toString())
                .containsEntry("account_id", fixture.accountId())
                .containsEntry("company_id", fixture.companyId())
                .containsEntry("error_code", "invalid_grant");
        assertThat(event.get("authorization_id")).isNotNull();
        assertThat(event.get("metadata").toString()).doesNotContain(tokens.code(), tokens.verifier());
    }

    @Test
    void userinfo_success_and_immediate_state_rejection_emit_correlated_events_without_token_material()
            throws Exception {
        ProtocolFixture fixture = protocolFixture();
        TokenSet tokens = issueTokens(fixture);

        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk());
        jdbcClient.sql("update accounts set status = 'DISABLED', updated_at = now() where id = :id")
                .param("id", fixture.accountId()).update();
        MvcResult rejected = mockMvc.perform(get("/userinfo")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsByteArray())
                .path("error").asText()).isEqualTo("invalid_token");

        List<Map<String, Object>> events = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       authorization_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId
                   and event_type in ('USERINFO_SUCCEEDED', 'USERINFO_DENIED')
                 order by id
                """).param("clientId", fixture.clientId()).query().listOfRows();
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("USERINFO_SUCCEEDED", "USERINFO_DENIED");
        assertThat(events.getFirst()).containsEntry("outcome", "SUCCESS");
        assertThat(events.getLast()).containsEntry("outcome", "DENIED")
                .containsEntry("error_code", "invalid_token");
        assertThat(events).allSatisfy(event -> {
            assertThat(event).containsEntry("subject", fixture.subject().toString())
                    .containsEntry("account_id", fixture.accountId())
                    .containsEntry("company_id", fixture.companyId());
            assertThat(event.get("authorization_id")).isNotNull();
            assertThat(event.get("metadata").toString()).doesNotContain(tokens.accessToken());
        });
    }

    @Test
    void every_userinfo_rejection_layer_records_one_sanitized_denied_event() throws Exception {
        long before = jdbcClient.sql("select coalesce(max(id), 0) from oauth_protocol_event")
                .query(Long.class).single();
        mockMvc.perform(get("/userinfo"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer "
                        + signedAccessToken(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60))))
                .andExpect(status().isUnauthorized());

        ProtocolFixture signedFixture = protocolFixture();
        TokenSet signed = issueTokens(signedFixture);
        String[] segments = signed.accessToken().split("\\.");
        segments[2] = (segments[2].startsWith("a") ? "b" : "a") + segments[2].substring(1);
        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer " + String.join(".", segments)))
                .andExpect(status().isUnauthorized());

        ProtocolFixture structuralFixture = protocolFixture();
        TokenSet structural = issueTokens(structuralFixture);
        jdbcClient.sql("""
                update oauth_authorization set id_token_issued_at = null, id_token_expires_at = null
                 where id = (select authorization_id from oauth_access_token
                              where access_token_hash = :hash)
                """).param("hash", sha256(structural.accessToken())).update();
        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer " + structural.accessToken()))
                .andExpect(status().isUnauthorized());

        List<Map<String, Object>> denied = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, error_code, metadata::text
                  from oauth_protocol_event
                 where id > :before and event_type = 'USERINFO_DENIED'
                 order by id
                """).param("before", before).query().listOfRows();
        assertThat(denied).hasSize(5).allSatisfy(event -> {
            assertThat(event).containsEntry("outcome", "DENIED")
                    .containsEntry("error_code", "invalid_token");
            assertThat(event.get("metadata").toString()).contains("USERINFO", "INVALID_TOKEN")
                    .doesNotContain("not-a-jwt", structural.accessToken(), signed.accessToken());
        });
    }

    @Test
    void app_revocation_revokes_only_the_selected_grant_and_preserves_the_idp_sso_session() throws Exception {
        ProtocolFixture fixture = protocolFixture("revoke-secret-" + UUID.randomUUID());
        TokenSet selected = issueTokens(fixture);
        TokenSet otherGrant = issueTokens(fixture);
        MockHttpSession idpSession = idpSession(fixture);

        mockMvc.perform(post("/oauth2/revoke").session(idpSession)
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .with(request -> {
                            request.setScheme("http");
                            request.setServerName("idp.localhost");
                            request.setServerPort(8080);
                            return request;
                        })
                        .header("Host", "idp.localhost:8080")
                        .header("Origin", ISSUER)
                        .param("token", selected.refreshToken())
                        .param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());

        assertThat(idpSession.isInvalid()).isFalse();
        assertThat(idpSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", selected.refreshToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", otherGrant.refreshToken()))
                .andExpect(status().isOk());

        Map<String, Object> event = jdbcClient.sql("""
                select event_type, outcome, client_id, subject::text, account_id, company_id,
                       authorization_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId and event_type = 'AUTHORIZATION_REVOKED'
                 order by id desc limit 1
                """).param("clientId", fixture.clientId()).query().singleRow();
        assertThat(event).containsEntry("outcome", "SUCCESS")
                .containsEntry("subject", fixture.subject().toString())
                .containsEntry("account_id", fixture.accountId())
                .containsEntry("company_id", fixture.companyId());
        assertThat(event.get("authorization_id")).isNotNull();
        assertThat(event.get("metadata").toString()).contains("CLIENT_SECRET_BASIC")
                .doesNotContain(selected.refreshToken());

        ProtocolFixture publicFixture = protocolFixture();
        TokenSet publicTokens = issueTokens(publicFixture);
        mockMvc.perform(post("/oauth2/revoke")
                        .param("client_id", publicFixture.clientId())
                        .param("token", publicTokens.accessToken())
                        .param("token_type_hint", "access_token"))
                .andExpect(status().isOk());
        String publicMetadata = jdbcClient.sql("""
                select metadata::text from oauth_protocol_event
                 where client_id = :clientId and event_type = 'AUTHORIZATION_REVOKED'
                   and outcome = 'SUCCESS'
                 order by id desc limit 1
                """).param("clientId", publicFixture.clientId()).query(String.class).single();
        assertThat(publicMetadata).contains("NONE").doesNotContain(publicTokens.accessToken());
    }

    @Test
    void confidential_bff_revocation_needs_no_origin_and_invalid_client_is_generic_audited_failure()
            throws Exception {
        ProtocolFixture fixture = protocolFixture("bff-revoke-secret-" + UUID.randomUUID());
        TokenSet tokens = issueTokens(fixture);

        MvcResult invalid = mockMvc.perform(post("/oauth2/revoke")
                        .with(httpBasic(fixture.clientId(), "wrong-secret"))
                        .param("token", tokens.refreshToken())
                        .param("token_type_hint", "refresh_token"))
                .andExpect(status().isUnauthorized()).andReturn();
        JsonNode invalidBody = objectMapper.readTree(invalid.getResponse().getContentAsByteArray());
        assertThat(invalidBody.fieldNames()).toIterable().containsExactly("error");
        assertThat(invalidBody.path("error").asText()).isEqualTo("invalid_client");
        assertThat(invalid.getResponse().getContentAsString())
                .doesNotContain("wrong-secret", tokens.refreshToken(), "Exception", "stack");

        mockMvc.perform(post("/oauth2/revoke")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("token", tokens.refreshToken())
                        .param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken()))
                .andExpect(status().isBadRequest());

        Map<String, Object> failedEvent = jdbcClient.sql("""
                select outcome, client_id, subject::text, error_code, metadata::text
                  from oauth_protocol_event
                 where event_type = 'AUTHORIZATION_REVOKED' and outcome = 'FAILURE'
                 order by id desc limit 1
                """).query().singleRow();
        assertThat(failedEvent).containsEntry("outcome", "FAILURE")
                .containsEntry("error_code", "invalid_client");
        assertThat(failedEvent.get("client_id")).isNull();
        assertThat(failedEvent.get("subject")).isNull();
        assertThat(failedEvent.get("metadata").toString())
                .doesNotContain("wrong-secret", tokens.refreshToken());
    }

    @Test
    void rp_initiated_logout_cryptographically_binds_the_hint_client_subject_session_and_exact_redirect()
            throws Exception {
        ProtocolFixture fixture = protocolFixture();
        TokenSet tokens = issueTokens(fixture);
        MockHttpSession session = idpSession(fixture, tokens.idToken());

        MvcResult result = mockMvc.perform(logoutRequest(session, tokens.idToken(), fixture.clientId(),
                        fixture.postLogoutRedirectUri(), "return-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        fixture.postLogoutRedirectUri() + "?state=return-state"))
                .andReturn();

        assertBrowserHeaders(result);
        assertThat(session.isInvalid()).isTrue();
        Map<String, Object> event = jdbcClient.sql("""
                select outcome, client_id, subject::text, account_id, company_id, error_code, metadata::text
                  from oauth_protocol_event
                 where client_id = :clientId and event_type = 'LOGOUT_COMPLETED'
                 order by id desc limit 1
                """).param("clientId", fixture.clientId()).query().singleRow();
        assertThat(event).containsEntry("outcome", "SUCCESS")
                .containsEntry("subject", fixture.subject().toString())
                .containsEntry("account_id", fixture.accountId())
                .containsEntry("company_id", fixture.companyId());
        assertThat(event.get("metadata").toString()).contains("session_invalidated").doesNotContain(tokens.idToken());

        MvcResult replay = mockMvc.perform(post("/connect/logout")
                        .with(csrf()).header("Origin", ISSUER)
                        .param("id_token_hint", tokens.idToken())
                        .param("client_id", fixture.clientId())
                        .param("post_logout_redirect_uri", fixture.postLogoutRedirectUri()))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/idp/error"))
                .andReturn();
        assertBrowserHeaders(replay);
        assertThat(replay.getResponse().getContentAsString()).doesNotContain(tokens.idToken());
    }

    @Test
    void rp_logout_rejects_a_same_user_client_and_second_hint_from_a_different_idp_session()
            throws Exception {
        ProtocolFixture fixture = protocolFixture();
        TokenSet tokens = issueTokens(fixture);
        String sid = jwtDecoder.decode(tokens.idToken()).getClaimAsString("sid");
        assertThat(sid).isNotBlank().matches("[0-9a-f-]{36}");

        MockHttpSession differentSession = idpSession(fixture, tokens.idToken(), false);
        mockMvc.perform(logoutRequest(differentSession, tokens.idToken(), fixture.clientId(),
                        fixture.postLogoutRedirectUri(), "cross-session"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/idp/error"));
        assertThat(differentSession.isInvalid()).isFalse();

        MockHttpSession boundSession = idpSession(fixture, tokens.idToken(), true);
        mockMvc.perform(logoutRequest(boundSession, tokens.idToken(), fixture.clientId(),
                        fixture.postLogoutRedirectUri(), "bound-session"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        fixture.postLogoutRedirectUri() + "?state=bound-session"));
        assertThat(boundSession.isInvalid()).isTrue();
    }

    @Test
    void rp_logout_rejects_expired_wrong_client_audience_subject_and_uri_without_redirect_or_session_loss()
            throws Exception {
        ProtocolFixture fixture = protocolFixture();
        ProtocolFixture other = protocolFixture();
        TokenSet tokens = issueTokens(fixture);
        Instant authTime = jwtDecoder.decode(tokens.idToken()).getClaimAsInstant("auth_time");
        String expired = signedIdToken(fixture.subject().toString(), fixture.clientId(), authTime,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        String wrongAudience = signedIdToken(fixture.subject().toString(), other.clientId(), authTime,
                Instant.now(), Instant.now().plusSeconds(300));
        String wrongSubject = signedIdToken(other.subject().toString(), fixture.clientId(), authTime,
                Instant.now(), Instant.now().plusSeconds(300));

        List<LogoutCase> cases = List.of(
                new LogoutCase(expired, fixture.clientId(), fixture.postLogoutRedirectUri()),
                new LogoutCase(tokens.idToken(), other.clientId(), fixture.postLogoutRedirectUri()),
                new LogoutCase(wrongAudience, other.clientId(), fixture.postLogoutRedirectUri()),
                new LogoutCase(wrongSubject, fixture.clientId(), fixture.postLogoutRedirectUri()),
                new LogoutCase(tokens.idToken(), fixture.clientId(), PUBLIC_ORIGIN + "/unregistered"),
                new LogoutCase("not.a.valid-jwt", fixture.clientId(), fixture.postLogoutRedirectUri()));

        for (LogoutCase invalid : cases) {
            MockHttpSession session = idpSession(fixture, tokens.idToken());
            MvcResult result = mockMvc.perform(logoutRequest(session, invalid.idTokenHint(),
                            invalid.clientId(), invalid.postLogoutRedirectUri(), "unsafe\r\nLocation: injected"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(forwardedUrl("/idp/error"))
                    .andReturn();
            assertBrowserHeaders(result);
            assertThat(session.isInvalid()).isFalse();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(invalid.idTokenHint()).doesNotContain("injected");
        }
    }

    @Test
    void actual_consent_approval_and_denial_emit_sanitized_events_and_keep_browser_headers() throws Exception {
        ProtocolFixture approvedFixture = protocolFixture();
        jdbcClient.sql("update oauth_client set trust = 'CONSENT_REQUIRED' where client_id = :clientId")
                .param("clientId", approvedFixture.clientId()).update();
        MockHttpSession approvedSession = idpSession(approvedFixture);
        ConsentPage approved = beginConsent(approvedFixture, approvedSession, "approved-state");
        assertBrowserHeaders(approved.response());

        MvcResult approval = mockMvc.perform(post("/oauth2/authorize").session(approvedSession).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", approvedFixture.clientId())
                        .param("state", approved.serverState())
                        .param("scope", "openid", "profile"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(approval);
        URI approvedCallback = URI.create(approval.getResponse().getHeader("Location"));
        assertThat(approvedCallback.toString()).startsWith(approvedFixture.redirectUri());
        assertThat(UriComponentsBuilder.fromUri(approvedCallback).build().getQueryParams().getFirst("state"))
                .isEqualTo("approved-state");
        assertThat(UriComponentsBuilder.fromUri(approvedCallback).build().getQueryParams().getFirst("code"))
                .isNotBlank();

        ProtocolFixture deniedFixture = protocolFixture();
        jdbcClient.sql("update oauth_client set trust = 'CONSENT_REQUIRED' where client_id = :clientId")
                .param("clientId", deniedFixture.clientId()).update();
        MockHttpSession deniedSession = idpSession(deniedFixture);
        ConsentPage denied = beginConsent(deniedFixture, deniedSession, "denied-state");
        MvcResult denial = mockMvc.perform(post("/idp/consent/deny").session(deniedSession).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", deniedFixture.clientId())
                        .param("state", denied.serverState()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertBrowserHeaders(denial);
        URI deniedCallback = URI.create(denial.getResponse().getHeader("Location"));
        assertThat(UriComponentsBuilder.fromUri(deniedCallback).build().getQueryParams().getFirst("error"))
                .isEqualTo("access_denied");
        assertThat(UriComponentsBuilder.fromUri(deniedCallback).build().getQueryParams().getFirst("state"))
                .isEqualTo("denied-state");

        List<Map<String, Object>> events = jdbcClient.sql("""
                        select event_type, outcome, client_id, subject::text, account_id, company_id,
                               authorization_id, error_code, metadata::text
                          from oauth_protocol_event
                         where client_id in (:approved, :denied)
                           and event_type in ('CONSENT_GRANTED', 'CONSENT_DENIED')
                         order by id
                        """).param("approved", approvedFixture.clientId())
                .param("denied", deniedFixture.clientId()).query().listOfRows();
        assertThat(events).hasSize(2);
        assertThat(events).anySatisfy(event -> assertThat(event)
                .containsEntry("event_type", "CONSENT_GRANTED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("client_id", approvedFixture.clientId())
                .containsEntry("subject", approvedFixture.subject().toString())
                .containsEntry("account_id", approvedFixture.accountId())
                .containsEntry("company_id", approvedFixture.companyId()));
        assertThat(events).anySatisfy(event -> assertThat(event)
                .containsEntry("event_type", "CONSENT_DENIED")
                .containsEntry("outcome", "DENIED")
                .containsEntry("error_code", "access_denied")
                .containsEntry("client_id", deniedFixture.clientId())
                .containsEntry("subject", deniedFixture.subject().toString()));
        assertThat(events).allSatisfy(event -> assertThat(event.get("metadata").toString())
                .doesNotContain("code").doesNotContain("token").doesNotContain("verifier"));
    }

    @Test
    void required_code_exchange_event_failure_rolls_back_code_consumption() throws Exception {
        ProtocolFixture fixture = protocolFixture("event-failure-secret-" + UUID.randomUUID());
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        MvcResult authorization = mockMvc.perform(get("/oauth2/authorize")
                        .session(idpSession(fixture))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", fixture.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "atomic-event-state")
                        .queryParam("nonce", "atomic-event-nonce")
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String code = UriComponentsBuilder.fromUri(
                        URI.create(authorization.getResponse().getHeader("Location"))).build()
                .getQueryParams().getFirst("code");

        jdbcClient.sql("""
                alter table oauth_protocol_event add constraint reject_code_exchange_event
                check (event_type <> 'AUTHORIZATION_CODE_EXCHANGED') not valid
                """).update();
        try {
            MvcResult failed = mockMvc.perform(post("/oauth2/token")
                            .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                            .param("grant_type", "authorization_code")
                            .param("code", code)
                            .param("redirect_uri", fixture.redirectUri())
                            .param("code_verifier", verifier))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(objectMapper.readTree(failed.getResponse().getContentAsByteArray()))
                    .isEqualTo(objectMapper.readTree("{\"error\":\"server_error\"}"));
            assertThat(jdbcClient.sql("select used_at from oauth_authorization_code where code_hash = :hash")
                    .param("hash", sha256(code)).query(Timestamp.class).optional()).isEmpty();
        } finally {
            jdbcClient.sql("alter table oauth_protocol_event drop constraint reject_code_exchange_event").update();
        }
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", fixture.redirectUri())
                        .param("code_verifier", verifier))
                .andExpect(status().isOk());
    }

    @Test
    void required_consent_refresh_and_revoke_events_are_atomic_with_protocol_state() throws Exception {
        ProtocolFixture consentFixture = protocolFixture();
        jdbcClient.sql("update oauth_client set trust = 'CONSENT_REQUIRED' where client_id = :clientId")
                .param("clientId", consentFixture.clientId()).update();
        MockHttpSession consentSession = idpSession(consentFixture);
        ConsentPage consent = beginConsent(consentFixture, consentSession, "atomic-consent-state");
        addRejectEventConstraint("reject_consent_event", "CONSENT_GRANTED");
        try {
            MvcResult response = mockMvc.perform(post("/oauth2/authorize").session(consentSession).with(csrf())
                            .header("Origin", ISSUER)
                            .param("client_id", consentFixture.clientId())
                            .param("state", consent.serverState())
                            .param("scope", "openid", "profile"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            assertThat(UriComponentsBuilder.fromUri(
                            URI.create(response.getResponse().getHeader("Location"))).build()
                    .getQueryParams().getFirst("error")).isEqualTo("server_error");
            assertThat(jdbcClient.sql("""
                            select count(*) from oauth_consent c join oauth_client oc on oc.id = c.registered_client_id
                             where c.principal_account_id = :accountId and oc.client_id = :clientId
                            """).param("accountId", consentFixture.accountId())
                    .param("clientId", consentFixture.clientId()).query(Long.class).single()).isZero();
        } finally {
            dropConstraint("reject_consent_event");
        }

        ProtocolFixture refreshFixture = protocolFixture("atomic-refresh-secret-" + UUID.randomUUID());
        TokenSet refreshTokens = issueTokens(refreshFixture);
        addRejectEventConstraint("reject_refresh_event", "REFRESH_ROTATED");
        try {
            MvcResult response = mockMvc.perform(post("/oauth2/token")
                            .with(httpBasic(refreshFixture.clientId(), refreshFixture.rawSecret()))
                            .param("grant_type", "refresh_token")
                            .param("refresh_token", refreshTokens.refreshToken()))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(objectMapper.readTree(response.getResponse().getContentAsByteArray()))
                    .isEqualTo(objectMapper.readTree("{\"error\":\"server_error\"}"));
            assertThat(jdbcClient.sql("select used_at from oauth_refresh_token where refresh_token_hash = :hash")
                    .param("hash", sha256(refreshTokens.refreshToken()))
                    .query(Timestamp.class).optional()).isEmpty();
        } finally {
            dropConstraint("reject_refresh_event");
        }
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(refreshFixture.clientId(), refreshFixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshTokens.refreshToken()))
                .andExpect(status().isOk());

        ProtocolFixture revokeFixture = protocolFixture("atomic-revoke-secret-" + UUID.randomUUID());
        TokenSet revokeTokens = issueTokens(revokeFixture);
        addRejectEventConstraint("reject_revoke_event", "AUTHORIZATION_REVOKED");
        try {
            MvcResult response = mockMvc.perform(post("/oauth2/revoke")
                            .with(httpBasic(revokeFixture.clientId(), revokeFixture.rawSecret()))
                            .param("token", revokeTokens.refreshToken())
                            .param("token_type_hint", "refresh_token"))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(objectMapper.readTree(response.getResponse().getContentAsByteArray()))
                    .isEqualTo(objectMapper.readTree("{\"error\":\"server_error\"}"));
            assertThat(jdbcClient.sql("""
                    select status from oauth_authorization where principal_account_id = :accountId
                    order by created_at desc limit 1
                    """).param("accountId", revokeFixture.accountId()).query(String.class).single())
                    .isEqualTo("ACTIVE");
        } finally {
            dropConstraint("reject_revoke_event");
        }
        mockMvc.perform(post("/oauth2/revoke")
                        .with(httpBasic(revokeFixture.clientId(), revokeFixture.rawSecret()))
                        .param("token", revokeTokens.refreshToken())
                        .param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());
        assertThat(jdbcClient.sql("""
                select status from oauth_authorization where principal_account_id = :accountId
                order by created_at desc limit 1
                """).param("accountId", revokeFixture.accountId()).query(String.class).single())
                .isEqualTo("REVOKED");
    }

    @Test
    void server_owned_trace_ids_keep_protocol_post_state_intact_for_malicious_headers() throws Exception {
        ProtocolFixture codeFixture = protocolFixture();
        String sensitiveTrace = "password=ProtocolPassword1234!";
        TokenSet codeTokens = issueTokens(codeFixture, sensitiveTrace);
        assertThat(codeTokens.accessToken()).isNotBlank();

        ProtocolFixture refreshFixture = protocolFixture("trace-refresh-secret-" + UUID.randomUUID());
        TokenSet refreshTokens = issueTokens(refreshFixture);
        String oversizedTrace = "x".repeat(256);
        MvcResult refresh = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(refreshFixture.clientId(), refreshFixture.rawSecret()))
                        .header("X-Trace-Id", oversizedTrace)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshTokens.refreshToken()))
                .andExpect(status().isOk()).andReturn();
        String rotatedRefresh = objectMapper.readTree(refresh.getResponse().getContentAsByteArray())
                .path("refresh_token").asText();
        assertThat(rotatedRefresh).isNotBlank().isNotEqualTo(refreshTokens.refreshToken());

        ProtocolFixture consentFixture = protocolFixture();
        jdbcClient.sql("update oauth_client set trust = 'CONSENT_REQUIRED' where client_id = :clientId")
                .param("clientId", consentFixture.clientId()).update();
        MockHttpSession consentSession = idpSession(consentFixture);
        ConsentPage consent = beginConsent(consentFixture, consentSession, "trace-consent-state");
        String splitTrace = "trace-safe\r\npassword=attacker";
        MvcResult approval = mockMvc.perform(post("/oauth2/authorize").session(consentSession).with(csrf())
                        .header("Origin", ISSUER)
                        .header("X-Trace-Id", splitTrace)
                        .param("client_id", consentFixture.clientId())
                        .param("state", consent.serverState())
                        .param("scope", "openid", "profile"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(UriComponentsBuilder.fromUri(
                        URI.create(approval.getResponse().getHeader("Location"))).build()
                .getQueryParams().getFirst("code")).isNotBlank();

        ProtocolFixture logoutFixture = protocolFixture();
        TokenSet logoutTokens = issueTokens(logoutFixture);
        MockHttpSession logoutSession = idpSession(logoutFixture, logoutTokens.idToken());
        mockMvc.perform(logoutRequest(logoutSession, logoutTokens.idToken(), logoutFixture.clientId(),
                        logoutFixture.postLogoutRedirectUri(), "trace-logout")
                        .header("X-Trace-Id", logoutTokens.idToken()))
                .andExpect(status().is3xxRedirection());
        assertThat(logoutSession.isInvalid()).isTrue();

        Long leaked = jdbcClient.sql("""
                        select count(*) from oauth_protocol_event
                         where correlation_id in (:sensitive, :oversized, :split, :logoutToken)
                            or metadata::text like :sensitivePattern
                            or metadata::text like :logoutPattern
                        """)
                .param("sensitive", sensitiveTrace)
                .param("oversized", oversizedTrace)
                .param("split", splitTrace)
                .param("logoutToken", logoutTokens.idToken())
                .param("sensitivePattern", "%" + sensitiveTrace + "%")
                .param("logoutPattern", "%" + logoutTokens.idToken() + "%")
                .query(Long.class).single();
        assertThat(leaked).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from oauth_protocol_event
                         where client_id in (:codeClient, :refreshClient, :consentClient, :logoutClient)
                           and correlation_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        """)
                .param("codeClient", codeFixture.clientId())
                .param("refreshClient", refreshFixture.clientId())
                .param("consentClient", consentFixture.clientId())
                .param("logoutClient", logoutFixture.clientId())
                .query(Long.class).single()).isZero();
    }

    private void assertBrowserHeaders(MvcResult result) {
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).isEqualTo(CSP);
    }

    private String registerPublicClient(String redirectUri, boolean active) {
        return registerClient(redirectUri, active, true);
    }

    private String registerClient(String redirectUri, boolean active, boolean publicClient) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", "CORS_" + suffix.toUpperCase())
                .param("domain", suffix + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long clientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'Public RP', :status, 'CONSENT_REQUIRED',
                                :publicClient, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", "cors-" + suffix)
                .param("status", active ? "ACTIVE" : "DISABLED")
                .param("publicClient", publicClient)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                values (:clientId, :redirectUri, 'AUTHORIZATION')
                """).param("clientId", clientId).param("redirectUri", redirectUri).update();
        jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'openid')")
                .param("clientId", clientId).update();
        return "cors-" + suffix;
    }

    private ProtocolFixture protocolFixture() {
        return protocolFixture(null);
    }

    private ProtocolFixture protocolFixture(String rawSecret) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        String companyCode = "EVT_" + suffix.toUpperCase();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", companyCode).param("domain", suffix + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                        """).param("companyId", companyId).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'Protocol User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                        """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 25)).param("positionId", positionId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, :passwordHash, 'ACTIVE', false, :now, :now) returning id
                        """).param("companyId", companyId).param("userId", userId)
                .param("email", "user@" + suffix + ".example")
                .param("passwordHash", passwordEncoder.encode("ProtocolPassword1234!"))
                .param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:accountId, 'USER')")
                .param("accountId", accountId).update();
        UUID subject = UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:id, :sub, :now)")
                .param("id", accountId).param("sub", subject).param("now", Timestamp.from(now)).update();
        String clientId = "event-client-" + suffix;
        long internalClientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'Protocol RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                                :publicClient, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", clientId)
                .param("publicClient", rawSecret == null)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        if (rawSecret != null) {
            jdbcClient.sql("""
                            insert into oauth_client_secret(client_id, secret_hash, secret_hint, created_at, version)
                            values (:clientId, :secretHash, :secretHint, :now, 0)
                            """).param("clientId", internalClientId)
                    .param("secretHash", new BCryptPasswordEncoder().encode(rawSecret))
                    .param("secretHint", rawSecret.substring(rawSecret.length() - 4))
                    .param("now", Timestamp.from(now)).update();
        }
        String redirectUri = PUBLIC_ORIGIN + "/acceptance/" + suffix;
        String postLogoutRedirectUri = PUBLIC_ORIGIN + "/logged-out/" + suffix;
        jdbcClient.sql("""
                insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                values (:clientId, :redirectUri, 'AUTHORIZATION')
                """).param("clientId", internalClientId).param("redirectUri", redirectUri).update();
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'POST_LOGOUT')
                        """).param("clientId", internalClientId)
                .param("redirectUri", postLogoutRedirectUri).update();
        for (String scope : List.of("openid", "profile")) {
            jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, :scope)")
                    .param("clientId", internalClientId).param("scope", scope).update();
        }
        return new ProtocolFixture(companyId, accountId, userId, subject, clientId,
                redirectUri, postLogoutRedirectUri,
                "user@" + suffix + ".example", rawSecret);
    }

    private String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private TokenSet issueTokens(ProtocolFixture fixture) throws Exception {
        return issueTokens(fixture, null);
    }

    private TokenSet issueTokens(ProtocolFixture fixture, String traceId) throws Exception {
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        MvcResult authorization = mockMvc.perform(get("/oauth2/authorize")
                        .session(idpSession(fixture))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", fixture.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "state-" + UUID.randomUUID())
                        .queryParam("nonce", "nonce-" + UUID.randomUUID())
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(authorization.getResponse().getHeader("Location"));
        String code = UriComponentsBuilder.fromUri(callback).build().getQueryParams().getFirst("code");
        var tokenRequest = post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", fixture.redirectUri())
                .param("code_verifier", verifier);
        if (fixture.rawSecret() == null) tokenRequest.param("client_id", fixture.clientId());
        else tokenRequest.with(httpBasic(fixture.clientId(), fixture.rawSecret()));
        if (traceId != null) tokenRequest.header("X-Trace-Id", traceId);
        MvcResult token = mockMvc.perform(tokenRequest)
                .andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(token.getResponse().getContentAsByteArray());
        return new TokenSet(json.path("access_token").asText(), json.path("refresh_token").asText(),
                json.path("id_token").asText(), code, verifier);
    }

    private String hidden(String html, String name) {
        var matcher = Pattern.compile("name=\\\"" + Pattern.quote(name) +
                "\\\"[^>]*value=\\\"([^\\\"]+)\\\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private MockHttpSession idpSession(ProtocolFixture fixture) {
        return idpSession(fixture, null);
    }

    private MockHttpSession idpSession(ProtocolFixture fixture, String idToken) {
        return idpSession(fixture, idToken, true);
    }

    private MockHttpSession idpSession(ProtocolFixture fixture, String idToken, boolean bindHint) {
        MockHttpSession session = new MockHttpSession();
        Instant authenticatedAt = idToken == null ? Instant.now()
                : jwtDecoder.decode(idToken).getClaimAsInstant("auth_time");
        var authentication = new IdpSessionAuthentication(fixture.accountId(), fixture.companyId(),
                fixture.userId(), java.util.Set.of(com.sweet.authstudy.identity.domain.AccountRole.USER),
                fixture.subject(), authenticatedAt,
                idToken != null && bindHint
                        ? UUID.fromString(jwtDecoder.decode(idToken).getClaimAsString("sid"))
                        : UUID.randomUUID());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(IdpLoginController.LAST_ACCESS_ATTRIBUTE, Instant.now());
        session.setAttribute(IdpLoginController.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, false);
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logoutRequest(
            MockHttpSession session, String idTokenHint, String clientId,
            String postLogoutRedirectUri, String state) {
        return post("/connect/logout").session(session).with(csrf())
                .header("Origin", ISSUER)
                .param("id_token_hint", idTokenHint)
                .param("client_id", clientId)
                .param("post_logout_redirect_uri", postLogoutRedirectUri)
                .param("state", state);
    }

    private String signedIdToken(String subject, String audience, Instant authTime,
                                 Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(subject)
                .audience(List.of(audience)).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("auth_time", java.util.Date.from(authTime.truncatedTo(ChronoUnit.SECONDS))).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private String signedAccessToken(Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(UUID.randomUUID().toString())
                .audience(List.of("auth-study-userinfo")).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("client_id", "invalid-entry-client").claim("scope", "openid")
                .id(UUID.randomUUID().toString()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private void assertExactError(MvcResult result, String error) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.fieldNames()).toIterable().containsExactly("error");
        assertThat(body.path("error").asText()).isEqualTo(error);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception", "stack", "message", "attacker-secret", "wrong-basic-secret");
    }

    private ConsentPage beginConsent(ProtocolFixture fixture, MockHttpSession session, String state)
            throws Exception {
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        MvcResult authorization = mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", fixture.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", state)
                        .queryParam("nonce", "nonce-" + UUID.randomUUID())
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI consentUri = URI.create(authorization.getResponse().getHeader("Location"));
        assertThat(consentUri.getPath()).isEqualTo("/idp/consent");
        MvcResult page = mockMvc.perform(get(consentUri).session(session))
                .andExpect(status().isOk()).andReturn();
        return new ConsentPage(hidden(page.getResponse().getContentAsString(), "state"), page);
    }

    private Object protocolMetadata(Map<String, ?> values) {
        try {
            Class<?> type = Class.forName("com.sweet.authstudy.oauth.domain.OAuthProtocolEvent$Metadata");
            return type.getMethod("from", Map.class).invoke(null, values);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("protocol metadata boundary is missing", exception);
        }
    }

    private void addRejectEventConstraint(String constraint, String eventType) {
        jdbcClient.sql("alter table oauth_protocol_event add constraint " + constraint
                + " check (event_type <> '" + eventType + "') not valid").update();
    }

    private void dropConstraint(String constraint) {
        jdbcClient.sql("alter table oauth_protocol_event drop constraint " + constraint).update();
    }

    private void recordProtocolEvent(OAuthProtocolEvent event) {
        try {
            OAuthProtocolEventService service = applicationContext.getBean(OAuthProtocolEventService.class);
            service.getClass().getMethod("record", OAuthProtocolEvent.class).invoke(service, event);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("protocol event persistence boundary is missing", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumValue(String className, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) Class.forName(className), name);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("protocol metadata type is missing", exception);
        }
    }

    private record ProtocolFixture(long companyId, long accountId, long userId, UUID subject,
                                   String clientId, String redirectUri, String postLogoutRedirectUri,
                                   String email, String rawSecret) {
    }

    private record LogoutCase(String idTokenHint, String clientId, String postLogoutRedirectUri) {
    }

    private record ConsentPage(String serverState, MvcResult response) {
    }

    private record CorsCase(String path, String method) {
    }

    private record TokenSet(String accessToken, String refreshToken, String idToken,
                            String code, String verifier) {
    }
}
