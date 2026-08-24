package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.sweet.authstudy.oauth.infrastructure.HrOAuthUserInfoClaimSource;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OidcUserInfoIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://userinfo-rp.example/callback");
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String COMPANY_CLAIM = "https://auth-study.local/claims/company";
    private static final String ORGANIZATION_CLAIM = "https://auth-study.local/claims/organization";
    private static final String ROLES_CLAIM = "https://auth-study.local/claims/roles";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private Environment environment;
    @MockitoSpyBean private HrOAuthUserInfoClaimSource claimSource;

    @Test
    void web_requests_do_not_share_a_persistence_context_across_security_and_userinfo_reads() {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class, true)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopeCases")
    void real_authorize_token_and_discovered_userinfo_publish_only_granted_claims(
            String ignoredName, Set<String> scopes, Set<String> expectedClaims) throws Exception {
        Discovery discovery = discovery();
        Fixture fixture = fixture();
        Tokens tokens = issue(discovery, fixture, scopes);

        JsonNode userInfo = userInfo(discovery.userInfoPath(), tokens.accessToken());
        SignedJWT idToken = SignedJWT.parse(tokens.idToken());
        SignedJWT accessToken = SignedJWT.parse(tokens.accessToken());

        assertThat(fieldNames(userInfo)).containsExactlyInAnyOrderElementsOf(expectedClaims);
        assertThat(userInfo.path("sub").asText())
                .isEqualTo(idToken.getJWTClaimsSet().getSubject())
                .isEqualTo(accessToken.getJWTClaimsSet().getSubject())
                .isEqualTo(fixture.subject().toString())
                .isNotEqualTo(Long.toString(fixture.accountId()));
        assertThat(idToken.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "sub", "aud", "exp", "iat", "auth_time", "nonce");
        assertThat(accessToken.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "sub", "aud", "client_id", "scope", "jti", "iat", "exp");
        assertThat(idToken.getJWTClaimsSet().getClaim(COMPANY_CLAIM)).isNull();
        assertThat(accessToken.getJWTClaimsSet().getClaim(COMPANY_CLAIM)).isNull();
        assertThat(idToken.getJWTClaimsSet().getClaim(ORGANIZATION_CLAIM)).isNull();
        assertThat(accessToken.getJWTClaimsSet().getClaim(ORGANIZATION_CLAIM)).isNull();
        assertThat(idToken.getJWTClaimsSet().getClaim(ROLES_CLAIM)).isNull();
        assertThat(accessToken.getJWTClaimsSet().getClaim(ROLES_CLAIM)).isNull();

        if (scopes.contains("profile")) {
            assertThat(userInfo.path("name").asText()).isEqualTo("Ada Lovelace");
        }
        if (scopes.contains("email")) {
            assertThat(userInfo.path("email").asText()).isEqualTo(fixture.email());
            assertThat(userInfo.path("email_verified").asBoolean()).isFalse();
        }
        if (scopes.contains("hr.company")) {
            assertThat(userInfo.path(COMPANY_CLAIM)).isEqualTo(objectMapper.readTree(
                    "{\"code\":\"" + fixture.companyCode() + "\",\"name\":\"Acme Corp\"}"));
        }
        if (scopes.contains("hr.organization")) {
            JsonNode organization = userInfo.path(ORGANIZATION_CLAIM);
            assertThat(fieldNames(organization)).containsExactlyInAnyOrder(
                    "position", "primary_department", "secondary_departments");
            assertThat(organization.path("position")).isEqualTo(
                    objectMapper.readTree("{\"code\":\"ENG\",\"name\":\"Engineer\"}"));
            assertThat(organization.path("primary_department")).isEqualTo(
                    objectMapper.readTree("{\"code\":\"PLATFORM\",\"name\":\"Platform\"}"));
            assertThat(organization.path("secondary_departments")).isEqualTo(objectMapper.readTree(
                    "[{\"code\":\"ALPHA\",\"name\":\"Alpha\"},"
                            + "{\"code\":\"ZETA\",\"name\":\"Zeta\"}]"));
        }
        if (scopes.contains("hr.roles")) {
            assertThat(toStrings(userInfo.path(ROLES_CLAIM))).containsExactly("COMPANY_ADMIN", "USER");
        }

        assertNoInternalOrSensitiveFields(userInfo, fixture);
    }

    @Test
    void every_post_issuance_state_and_ownership_failure_is_the_same_standard_invalid_token()
            throws Exception {
        Discovery discovery = discovery();
        Fixture fixture = fixture();
        Tokens tokens = issue(discovery, fixture, allScopes());
        assertThat(userInfo(discovery.userInfoPath(), tokens.accessToken()).path("sub").asText())
                .isEqualTo(fixture.subject().toString());

        String authorizationId = jdbcClient.sql("""
                select authorization_id from oauth_access_token where access_token_hash = :hash
                """).param("hash", sha256(tokens.accessToken())).query(String.class).single();
        Instant authorizationExpiry = jdbcClient.sql(
                "select expires_at from oauth_authorization where id = :id")
                .param("id", authorizationId).query(Instant.class).single();
        Instant authorizationCreatedAt = jdbcClient.sql(
                "select created_at from oauth_authorization where id = :id")
                .param("id", authorizationId).query(Instant.class).single();
        Instant tokenExpiry = jdbcClient.sql(
                "select expires_at from oauth_access_token where authorization_id = :id")
                .param("id", authorizationId).query(Instant.class).single();
        Instant tokenIssuedAt = jdbcClient.sql(
                "select issued_at from oauth_access_token where authorization_id = :id")
                .param("id", authorizationId).query(Instant.class).single();

        assertInvalidAfter(discovery, tokens, "update accounts set locked_until = :value where id = :id",
                fixture.accountId(), Timestamp.from(Instant.now().plusSeconds(300)));
        reset("update accounts set locked_until = null where id = :id", fixture.accountId());

        assertInvalidAfter(discovery, tokens, "update accounts set must_change_password = true where id = :id",
                fixture.accountId(), null);
        reset("update accounts set must_change_password = false where id = :id", fixture.accountId());

        assertInvalidAfter(discovery, tokens, "update accounts set status = 'DISABLED' where id = :id",
                fixture.accountId(), null);
        reset("update accounts set status = 'ACTIVE' where id = :id", fixture.accountId());

        assertInvalidAfter(discovery, tokens, "update users set status = 'LOCKED' where id = :id",
                fixture.userId(), null);
        reset("update users set status = 'ACTIVE' where id = :id", fixture.userId());

        assertInvalidAfter(discovery, tokens, "update companies set status = 'INACTIVE' where id = :id",
                fixture.companyId(), null);
        reset("update companies set status = 'ACTIVE' where id = :id", fixture.companyId());

        assertInvalidAfter(discovery, tokens, "update oauth_client set status = 'DISABLED' where id = :id",
                fixture.internalClientId(), null);
        reset("update oauth_client set status = 'ACTIVE' where id = :id", fixture.internalClientId());

        assertInvalidAfter(discovery, tokens, """
                update oauth_authorization
                   set status = 'REVOKED', revocation_reason = 'TEST', revoked_at = now()
                 where id = :id
                """, authorizationId, null);
        reset("""
                update oauth_authorization
                   set status = 'ACTIVE', revocation_reason = null, revoked_at = null
                 where id = :id
                """, authorizationId);

        assertInvalidAfter(discovery, tokens,
                "update oauth_authorization set expires_at = :value where id = :id",
                authorizationId, Timestamp.from(authorizationCreatedAt.plusMillis(1)));
        updateTime("update oauth_authorization set expires_at = :value where id = :id",
                authorizationId, authorizationExpiry);

        assertInvalidAfter(discovery, tokens,
                "update oauth_access_token set revoked_at = now() where authorization_id = :id",
                authorizationId, null);
        reset("update oauth_access_token set revoked_at = null where authorization_id = :id", authorizationId);

        assertInvalidAfter(discovery, tokens,
                "update oauth_access_token set expires_at = :value where authorization_id = :id",
                authorizationId, Timestamp.from(tokenIssuedAt.plusMillis(1)));
        updateTime("update oauth_access_token set expires_at = :value where authorization_id = :id",
                authorizationId, tokenExpiry);

        long alternateClient = alternateClient(fixture);
        assertInvalidAfter(discovery, tokens,
                "update oauth_authorization set registered_client_id = :value where id = :id",
                authorizationId, alternateClient);
        updateLong("update oauth_authorization set registered_client_id = :value where id = :id",
                authorizationId, fixture.internalClientId());

        AlternateSubject alternateSubject = alternateSubject(fixture);
        jdbcClient.sql("""
                update oauth_authorization
                   set subject = :subject, principal_account_id = :accountId
                 where id = :id
                """).param("subject", alternateSubject.subject())
                .param("accountId", alternateSubject.accountId()).param("id", authorizationId).update();
        assertInvalid(discovery.userInfoPath(), tokens.accessToken());
        jdbcClient.sql("""
                update oauth_authorization
                   set subject = :subject, principal_account_id = :accountId
                 where id = :id
                """).param("subject", fixture.subject()).param("accountId", fixture.accountId())
                .param("id", authorizationId).update();

        String alternateAuthorization = copyAuthorization(authorizationId, alternateSubject);
        jdbcClient.sql("update oauth_access_token set authorization_id = :other where authorization_id = :id")
                .param("other", alternateAuthorization).param("id", authorizationId).update();
        assertInvalid(discovery.userInfoPath(), tokens.accessToken());
        jdbcClient.sql("update oauth_access_token set authorization_id = :id where authorization_id = :other")
                .param("id", authorizationId).param("other", alternateAuthorization).update();
    }

    @Test
    void authorization_revoked_after_sas_lookup_is_not_hidden_by_the_request_persistence_context()
            throws Exception {
        raceAfterSasLookup((fixture, authorizationId) -> jdbcClient.sql("""
                update oauth_authorization
                   set status = 'REVOKED', revocation_reason = 'RACE', revoked_at = now()
                 where id = :id
                """).param("id", authorizationId).update());
    }

    @Test
    void client_disabled_after_sas_lookup_is_not_hidden_by_the_request_persistence_context()
            throws Exception {
        raceAfterSasLookup((fixture, authorizationId) -> jdbcClient.sql("""
                update oauth_client set status = 'DISABLED' where id = :id
                """).param("id", fixture.internalClientId()).update());
    }

    @Test
    void openid_access_token_without_persisted_id_token_issuance_evidence_is_invalid()
            throws Exception {
        Discovery discovery = discovery();
        Fixture fixture = fixture();
        Tokens tokens = issue(discovery, fixture, Set.of("openid"));
        String authorizationId = authorizationId(tokens.accessToken());

        assertThat(jdbcClient.sql("""
                select count(*) from oauth_authorization
                 where id = :id
                   and id_token_issued_at is not null
                   and id_token_expires_at is not null
                """).param("id", authorizationId).query(Long.class).single()).isOne();
        jdbcClient.sql("""
                update oauth_authorization
                   set id_token_issued_at = null, id_token_expires_at = null
                 where id = :id
                """).param("id", authorizationId).update();

        assertInvalid(discovery.userInfoPath(), tokens.accessToken());
    }

    @Test
    void access_token_without_openid_is_not_valid_for_oidc_userinfo() throws Exception {
        Discovery discovery = discovery();
        Fixture fixture = fixture();
        Tokens tokens = issue(discovery, fixture, Set.of("profile"));
        String authorizationId = authorizationId(tokens.accessToken());

        assertThat(tokens.idToken()).isEmpty();
        assertThat(jdbcClient.sql("""
                select count(*) from oauth_authorization
                 where id = :id
                   and id_token_issued_at is null
                   and id_token_expires_at is null
                """).param("id", authorizationId).query(Long.class).single()).isOne();
        assertInvalid(discovery.userInfoPath(), tokens.accessToken());
    }

    private void raceAfterSasLookup(BiConsumer<Fixture, String> mutation) throws Exception {
        Discovery discovery = discovery();
        Fixture fixture = fixture();
        Tokens tokens = issue(discovery, fixture, allScopes());
        String authorizationId = jdbcClient.sql("""
                select authorization_id from oauth_access_token where access_token_hash = :hash
                """).param("hash", sha256(tokens.accessToken())).query(String.class).single();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        doAnswer(invocation -> {
            sourceEntered.countDown();
            if (!releaseSource.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to resume UserInfo claim source.");
            }
            return invocation.callRealMethod();
        }).when(claimSource).load(eq(tokens.accessToken()));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var response = executor.submit(() -> mockMvc.perform(get(discovery.userInfoPath())
                    .header("Authorization", "Bearer " + tokens.accessToken())).andReturn());
            assertThat(sourceEntered.await(10, TimeUnit.SECONDS)).isTrue();
            mutation.accept(fixture, authorizationId);
            releaseSource.countDown();
            assertInvalid(response.get(10, TimeUnit.SECONDS));
        } finally {
            releaseSource.countDown();
            executor.shutdownNow();
            org.mockito.Mockito.reset(claimSource);
        }
    }

    private String authorizationId(String accessToken) throws Exception {
        return jdbcClient.sql("""
                select authorization_id from oauth_access_token where access_token_hash = :hash
                """).param("hash", sha256(accessToken)).query(String.class).single();
    }

    private static Stream<Arguments> scopeCases() {
        return Stream.of(
                scope("openid only", Set.of("openid"), "sub"),
                scope("profile only", Set.of("openid", "profile"), "sub", "name"),
                scope("email only", Set.of("openid", "email"), "sub", "email", "email_verified"),
                scope("company only", Set.of("openid", "hr.company"), "sub", COMPANY_CLAIM),
                scope("organization only", Set.of("openid", "hr.organization"), "sub", ORGANIZATION_CLAIM),
                scope("roles only", Set.of("openid", "hr.roles"), "sub", ROLES_CLAIM),
                scope("all scopes", allScopes(), "sub", "name", "email",
                        "email_verified", COMPANY_CLAIM, ORGANIZATION_CLAIM, ROLES_CLAIM));
    }

    private static Arguments scope(String name, Set<String> scopes, String... claims) {
        return Arguments.of(name, scopes, Set.of(claims));
    }

    private static Set<String> allScopes() {
        return Set.of("openid", "profile", "email", "hr.company", "hr.organization", "hr.roles");
    }

    private Discovery discovery() throws Exception {
        JsonNode json = json(mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.path("issuer").asText()).isEqualTo(ISSUER);
        return new Discovery(
                URI.create(json.path("authorization_endpoint").asText()).getPath(),
                URI.create(json.path("token_endpoint").asText()).getPath(),
                URI.create(json.path("userinfo_endpoint").asText()).getPath());
    }

    private Tokens issue(Discovery discovery, Fixture fixture, Set<String> scopes) throws Exception {
        String state = "state-" + UUID.randomUUID();
        String nonce = "nonce-" + UUID.randomUUID();
        MvcResult authorize = mockMvc.perform(get(discovery.authorizationPath())
                        .session(new MockHttpSession())
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", String.join(" ", scopes))
                        .queryParam("state", state)
                        .queryParam("nonce", nonce)
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(authorize.getResponse().getHeader("Location"));
        assertThat(query(callback, "state")).isEqualTo(state);
        String code = query(callback, "code");

        MvcResult exchange = mockMvc.perform(post(discovery.tokenPath())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", VERIFIER)
                        .param("client_id", fixture.clientId()))
                .andExpect(status().isOk()).andReturn();
        JsonNode response = json(exchange);
        return new Tokens(response.path("id_token").asText(), response.path("access_token").asText());
    }

    private JsonNode userInfo(String path, String accessToken) throws Exception {
        return json(mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn());
    }

    private void assertInvalidAfter(
            Discovery discovery, Tokens tokens, String sql, Object id, Object value) throws Exception {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).param("id", id);
        if (value != null) statement = statement.param("value", value);
        statement.update();
        assertInvalid(discovery.userInfoPath(), tokens.accessToken());
    }

    private void assertInvalid(String path, String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andReturn();
        assertInvalid(result);
    }

    private void assertInvalid(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer error=\"invalid_token\"");
        JsonNode error = json(result);
        assertThat(fieldNames(error)).containsExactly("error");
        assertThat(error.path("error").asText()).isEqualTo("invalid_token");
    }

    private void reset(String sql, Object id) {
        jdbcClient.sql(sql).param("id", id).update();
    }

    private void updateTime(String sql, Object id, Instant value) {
        jdbcClient.sql(sql).param("id", id).param("value", Timestamp.from(value)).update();
    }

    private void updateLong(String sql, Object id, long value) {
        jdbcClient.sql(sql).param("id", id).param("value", value).update();
    }

    private long alternateClient(Fixture fixture) {
        Instant now = Instant.now();
        String clientId = "alternate-client-" + UUID.randomUUID();
        long id = jdbcClient.sql("""
                insert into oauth_client(company_id, client_id, display_name, status, trust,
                                         public_client, created_at, updated_at)
                values (:companyId, :clientId, 'Alternate RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                        true, :now, :now) returning id
                """).param("companyId", fixture.companyId()).param("clientId", clientId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                values (:clientId, :redirectUri, 'AUTHORIZATION')
                """).param("clientId", id).param("redirectUri", CALLBACK.toString()).update();
        for (String scope : allScopes()) {
            jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, :scope)")
                    .param("clientId", id).param("scope", scope).update();
        }
        return id;
    }

    private AlternateSubject alternateSubject(Fixture fixture) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        long userId = jdbcClient.sql("""
                insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                  position_id, status, created_at, updated_at)
                values (:companyId, :code, :employeeNumber, 'Alternate User', '010-0000-0000', :hiredAt,
                        'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                """).param("companyId", fixture.companyId()).param("code", "ALT-" + suffix)
                .param("employeeNumber", "E-ALT-" + suffix).param("hiredAt", LocalDate.of(2026, 8, 24))
                .param("positionId", fixture.positionId()).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long accountId = jdbcClient.sql("""
                insert into accounts(company_id, user_id, login_email, password_hash, status,
                                     must_change_password, created_at, updated_at)
                values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now) returning id
                """).param("companyId", fixture.companyId()).param("userId", userId)
                .param("email", "alt-" + suffix + "@example.com").param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:id, 'USER')")
                .param("id", accountId).update();
        UUID subject = UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:id, :subject, :now)")
                .param("id", accountId).param("subject", subject).param("now", Timestamp.from(now)).update();
        return new AlternateSubject(accountId, subject);
    }

    private String copyAuthorization(String sourceId, AlternateSubject alternateSubject) {
        String id = "copied-" + UUID.randomUUID();
        jdbcClient.sql("""
                insert into oauth_authorization(
                    id, registered_client_id, subject, principal_account_id, company_id,
                    authorization_grant_type, authorized_scopes, attributes, server_state_hash,
                    authenticated_at, status, revocation_reason, created_at, expires_at, revoked_at)
                select :newId, registered_client_id, :subject, :accountId, company_id,
                       authorization_grant_type, authorized_scopes, attributes, null,
                       authenticated_at, 'ACTIVE', null, created_at, expires_at, null
                  from oauth_authorization where id = :sourceId
                """).param("newId", id).param("subject", alternateSubject.subject())
                .param("accountId", alternateSubject.accountId()).param("sourceId", sourceId).update();
        return id;
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String companyCode = "UI_" + suffix.toUpperCase();
        String userCode = "USR-" + suffix.toUpperCase();
        String email = suffix + "@example.com";
        String employeeNumber = "E-INTERNAL-" + suffix;
        Instant now = Instant.now();
        long companyId = jdbcClient.sql("""
                insert into companies(code, name, email_domain, status, created_at, updated_at)
                values (:code, 'Acme Corp', :domain, 'ACTIVE', :now, :now) returning id
                """).param("code", companyCode).param("domain", companyCode.toLowerCase() + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                values (:companyId, 'ENG', 'Engineer', 1, 1, true, :now, :now) returning id
                """).param("companyId", companyId).param("now", Timestamp.from(now)).query(Long.class).single();
        long userId = jdbcClient.sql("""
                insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                  position_id, status, created_at, updated_at)
                values (:companyId, :userCode, :employeeNumber, 'Ada Lovelace', '010-0000-0000', :hiredAt,
                        'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                """).param("companyId", companyId).param("userCode", userCode)
                .param("employeeNumber", employeeNumber).param("hiredAt", LocalDate.of(2026, 8, 24))
                .param("positionId", positionId).param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                insert into accounts(company_id, user_id, login_email, password_hash, status,
                                     must_change_password, created_at, updated_at)
                values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now) returning id
                """).param("companyId", companyId).param("userId", userId).param("email", email)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:id, 'USER'), (:id, 'COMPANY_ADMIN')")
                .param("id", accountId).update();
        UUID subject = UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:id, :subject, :now)")
                .param("id", accountId).param("subject", subject).param("now", Timestamp.from(now)).update();

        insertMembership(companyId, userId, department(companyId, "ZETA", "Zeta", now), false, now);
        insertMembership(companyId, userId, department(companyId, "PLATFORM", "Platform", now), true, now);
        insertMembership(companyId, userId, department(companyId, "ALPHA", "Alpha", now), false, now);

        String clientId = "userinfo-client-" + suffix;
        long internalClientId = jdbcClient.sql("""
                insert into oauth_client(company_id, client_id, display_name, status, trust,
                                         public_client, created_at, updated_at)
                values (:companyId, :clientId, 'UserInfo RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                        true, :now, :now) returning id
                """).param("companyId", companyId).param("clientId", clientId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                values (:clientId, :redirectUri, 'AUTHORIZATION')
                """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        for (String scope : allScopes()) {
            jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, :scope)")
                    .param("clientId", internalClientId).param("scope", scope).update();
        }
        return new Fixture(companyId, userId, accountId, positionId, internalClientId,
                companyCode, userCode, email, employeeNumber, subject, clientId);
    }

    private long department(long companyId, String code, String name, Instant now) {
        return jdbcClient.sql("""
                insert into departments(company_id, code, name, status, created_at, updated_at)
                values (:companyId, :code, :name, 'ACTIVE', :now, :now) returning id
                """).param("companyId", companyId).param("code", code).param("name", name)
                .param("now", Timestamp.from(now)).query(Long.class).single();
    }

    private void insertMembership(long companyId, long userId, long departmentId, boolean primary, Instant now) {
        jdbcClient.sql("""
                insert into department_memberships(
                    company_id, user_id, department_id, role, is_primary, started_at, created_at, updated_at)
                values (:companyId, :userId, :departmentId, 'MEMBER', :primary, :now, :now, :now)
                """).param("companyId", companyId).param("userId", userId)
                .param("departmentId", departmentId).param("primary", primary)
                .param("now", Timestamp.from(now)).update();
    }

    private void assertNoInternalOrSensitiveFields(JsonNode node, Fixture fixture) {
        List<String> names = new ArrayList<>();
        List<String> textValues = new ArrayList<>();
        List<JsonNode> numericValues = new ArrayList<>();
        collectNames(node, names);
        collectScalarValues(node, textValues, numericValues);
        assertThat(names).doesNotContain(
                "id", "account_id", "company_id", "user_id", "department_id", "position_id",
                "employee_number", "employeeNumber", "password", "password_hash",
                "must_change_password", "locked_until");
        assertThat(textValues).doesNotContain(
                Long.toString(fixture.accountId()), fixture.userCode(), fixture.employeeNumber(), "password_hash");
        assertThat(numericValues).isEmpty();
    }

    private void collectScalarValues(JsonNode node, List<String> textValues, List<JsonNode> numericValues) {
        if (node.isContainerNode()) {
            node.forEach(value -> collectScalarValues(value, textValues, numericValues));
        } else if (node.isTextual()) {
            textValues.add(node.asText());
        } else if (node.isNumber()) {
            numericValues.add(node);
        }
    }

    private void collectNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                collectNames(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(value -> collectNames(value, names));
        }
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static LinkedHashSet<String> fieldNames(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> toStrings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private record Discovery(String authorizationPath, String tokenPath, String userInfoPath) { }
    private record Tokens(String idToken, String accessToken) { }
    private record AlternateSubject(long accountId, UUID subject) { }
    private record Fixture(
            long companyId,
            long userId,
            long accountId,
            long positionId,
            long internalClientId,
            String companyCode,
            String userCode,
            String email,
            String employeeNumber,
            UUID subject,
            String clientId) { }
}
