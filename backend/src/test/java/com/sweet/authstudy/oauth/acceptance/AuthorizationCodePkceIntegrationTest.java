package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class,
        AuthorizationCodePkceIntegrationTest.ProtocolTestConfiguration.class})
@ActiveProfiles("test")
class AuthorizationCodePkceIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://rp.example/callback?source=idp");
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String WRONG_VERIFIER =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;

    @Test
    void discovery_driven_authorization_code_exchange_succeeds_exactly_once() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        assertThat(Duration.between(
                jdbcInstant("select issued_at from oauth_authorization_code where authorization_id = :id", issued.authorizationId()),
                jdbcInstant("select expires_at from oauth_authorization_code where authorization_id = :id", issued.authorizationId())))
                .isEqualTo(Duration.ofSeconds(60));

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"));

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
    }

    @Test
    void missing_verifier_consumes_the_code_and_returns_invalid_grant() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        mockMvc.perform(post(endpoints.tokenPath())
                        .header("Origin", ISSUER)
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", issued.code())
                        .param("redirect_uri", CALLBACK.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void plain_pkce_method_is_rejected_as_invalid_request_before_a_code_is_issued() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();

        MvcResult result = mockMvc.perform(get(endpoints.authorizationPath())
                        .session(new MockHttpSession())
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", "plain-state")
                        .queryParam("nonce", "plain-nonce")
                        .queryParam("code_challenge", VERIFIER)
                        .queryParam("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        URI location = URI.create(result.getResponse().getHeader("Location"));
        assertThat(query(location, "error")).isEqualTo("invalid_request");
        assertThat(query(location, "state")).isEqualTo("plain-state");
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization where registered_client_id = :id")
                .param("id", fixture.internalClientId()).query(Long.class).single()).isZero();
    }

    @Test
    void wrong_verifier_consumes_the_code_and_replay_with_the_right_verifier_stays_invalid_grant()
            throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), WRONG_VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void an_expired_code_is_consumed_and_returns_invalid_grant() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");
        jdbcClient.sql("update oauth_authorization_code set expires_at = issued_at + interval '1 millisecond' where authorization_id = :id")
                .param("id", issued.authorizationId()).update();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
    }

    @Test
    void two_concurrent_http_exchanges_cannot_both_succeed() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> exchangeAfterBarrier(start, endpoints, fixture, issued)),
                    executor.submit(() -> exchangeAfterBarrier(start, endpoints, fixture, issued)));
            List<ExchangeResult> results = futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(results).extracting(ExchangeResult::status)
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(results.stream().filter(result -> result.status() == 400)
                    .map(ExchangeResult::error).toList()).containsExactly("invalid_grant");
        }
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(jdbcClient.sql("select count(*) from oauth_access_token where authorization_id = :id")
                .param("id", issued.authorizationId()).query(Long.class).single()).isEqualTo(1L);
    }

    private ExchangeResult exchangeAfterBarrier(CyclicBarrier start, Endpoints endpoints,
            Fixture fixture, IssuedCode issued) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER)).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new ExchangeResult(result.getResponse().getStatus(),
                json.path("error").isMissingNode() ? null : json.path("error").asText());
    }

    private IssuedCode authorize(Endpoints endpoints, Fixture fixture, String verifier, String method)
            throws Exception {
        String state = "state-" + UUID.randomUUID();
        String nonce = "nonce-" + UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(get(endpoints.authorizationPath())
                        .session(session)
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", state)
                        .queryParam("nonce", nonce)
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", method))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI location = URI.create(result.getResponse().getHeader("Location"));
        assertThat(location.getScheme() + "://" + location.getAuthority() + location.getPath())
                .isEqualTo("https://rp.example/callback");
        assertThat(query(location, "state")).isEqualTo(state);
        assertThat(query(location, "error")).isNull();
        String code = query(location, "code");
        assertThat(code).isNotBlank();
        String authorizationId = jdbcClient.sql("""
                        select authorization_id from oauth_authorization_code where code_hash = :hash
                        """).param("hash", sha256(code)).query(String.class).single();
        return new IssuedCode(code, authorizationId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
            Endpoints endpoints, Fixture fixture, String code, String verifier) {
        return post(endpoints.tokenPath())
                .header("Origin", ISSUER)
                .param("grant_type", "authorization_code")
                .param("client_id", fixture.clientId())
                .param("code", code)
                .param("redirect_uri", CALLBACK.toString())
                .param("code_verifier", verifier);
    }

    private Endpoints discovery() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        URI authorizationEndpoint = URI.create(discovery.path("authorization_endpoint").asText());
        URI tokenEndpoint = URI.create(discovery.path("token_endpoint").asText());
        assertThat(discovery.path("issuer").asText()).isEqualTo(ISSUER);
        return new Endpoints(authorizationEndpoint.getPath(), tokenEndpoint.getPath());
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "PKCE_" + suffix.toUpperCase();
        Instant now = Instant.now();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", code).param("domain", code.toLowerCase() + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                        """).param("companyId", companyId).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                        """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 21)).param("positionId", positionId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now) returning id
                        """).param("companyId", companyId).param("userId", userId)
                .param("email", suffix + "@example.com").param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:accountId, :subject, :now)")
                .param("accountId", accountId).param("subject", UUID.randomUUID())
                .param("now", Timestamp.from(now)).update();
        String clientId = "public-" + suffix;
        long internalClientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'PKCE RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                                true, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", clientId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'AUTHORIZATION')
                        """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'openid')")
                .param("clientId", internalClientId).update();
        return new Fixture(accountId, internalClientId, clientId);
    }

    private Instant jdbcInstant(String sql, String authorizationId) {
        return jdbcClient.sql(sql).param("id", authorizationId).query(Instant.class).single();
    }

    private Instant codeUsedAt(String authorizationId) {
        return jdbcClient.sql("select used_at from oauth_authorization_code where authorization_id = :id")
                .param("id", authorizationId).query(Instant.class).optional().orElse(null);
    }

    private String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Endpoints(String authorizationPath, String tokenPath) { }
    private record Fixture(long accountId, long internalClientId, String clientId) { }
    private record IssuedCode(String code, String authorizationId) { }
    private record ExchangeResult(int status, String error) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtocolTestConfiguration {
        @Bean
        JWKSource<SecurityContext> taskSevenTestJwkSource() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("task-seven-test-rs256")
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }
    }
}
