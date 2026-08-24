package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import com.sweet.authstudy.oauth.infrastructure.OAuthPrivateKeyCipher;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OidcDiscoveryAndTokenContractIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://oidc-rp.example/callback");
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private OAuthSigningKeyRepository signingKeys;
    @Autowired private OAuthPrivateKeyCipher cipher;
    @Autowired private JWKSource<SecurityContext> jwkSource;
    @Autowired @Qualifier("oauthJwtDecoder") private JwtDecoder jwtDecoder;

    @Test
    void discovery_jwks_and_real_code_exchange_publish_the_rs256_allowlist_contract() throws Exception {
        OAuthSigningKey oldActive = signingKeys.findActive().orElseThrow();
        OAuthSigningKey active = signingKeys.rotate(
                () -> generatedKey("contract-" + UUID.randomUUID()), Instant.now());

        Discovery discovery = discovery();
        JsonNode jwksJson = getJson(discovery.jwksPath());
        JWKSet jwks = JWKSet.parse(jwksJson.toString());
        assertThat(jwks.getKeys()).extracting(com.nimbusds.jose.jwk.JWK::getKeyID)
                .contains(oldActive.kid(), active.kid());
        assertThat(jwksJson.toString()).doesNotContain(
                "\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\"");

        Fixture fixture = fixture();
        String nonce = "nonce-" + UUID.randomUUID();
        String code = authorize(discovery.authorizationPath(), fixture, nonce);
        JsonNode response = exchange(discovery.tokenPath(), fixture, code);
        SignedJWT idToken = SignedJWT.parse(response.path("id_token").asText());
        SignedJWT accessToken = SignedJWT.parse(response.path("access_token").asText());

        RSAKey publishedActive = jwks.getKeyByKeyId(active.kid()).toRSAKey();
        assertThat(idToken.verify(new RSASSAVerifier(publishedActive))).isTrue();
        assertThat(accessToken.verify(new RSASSAVerifier(publishedActive))).isTrue();
        assertHeader(idToken, active.kid());
        assertHeader(accessToken, active.kid());

        String expectedSubject = jdbcClient.sql("""
                select subject::text from oauth_subject where account_id = :accountId
                """).param("accountId", fixture.accountId()).query(String.class).single();
        JWTClaimsSet idClaims = idToken.getJWTClaimsSet();
        JWTClaimsSet accessClaims = accessToken.getJWTClaimsSet();
        assertThat(idClaims.getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "sub", "aud", "exp", "iat", "auth_time", "nonce");
        assertThat(idClaims.getIssuer()).isEqualTo(ISSUER);
        assertThat(idClaims.getSubject()).isEqualTo(expectedSubject)
                .isNotEqualTo(Long.toString(fixture.accountId()));
        assertThat(idClaims.getAudience()).containsExactly(fixture.clientId());
        assertThat(idClaims.getStringClaim("nonce")).isEqualTo(nonce);
        assertThat(Duration.between(idClaims.getIssueTime().toInstant(), idClaims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));

        Instant authenticatedAt = jdbcClient.sql("""
                select authenticated_at from oauth_authorization
                where principal_account_id = :accountId order by created_at desc limit 1
                """).param("accountId", fixture.accountId()).query(Instant.class).single();
        assertThat(idClaims.getDateClaim("auth_time").toInstant().getEpochSecond())
                .isEqualTo(authenticatedAt.getEpochSecond());

        assertThat(accessClaims.getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "sub", "aud", "client_id", "scope", "jti", "iat", "exp");
        assertThat(accessClaims.getIssuer()).isEqualTo(ISSUER);
        assertThat(accessClaims.getSubject()).isEqualTo(expectedSubject).isEqualTo(idClaims.getSubject());
        assertThat(accessClaims.getAudience()).containsExactly("auth-study-userinfo");
        assertThat(accessClaims.getStringClaim("client_id")).isEqualTo(fixture.clientId());
        assertThat(accessClaims.getStringClaim("scope")).isEqualTo("hr.roles openid profile");
        assertThat(accessClaims.getJWTID()).isNotBlank();
        assertThat(Duration.between(
                accessClaims.getIssueTime().toInstant(), accessClaims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(accessClaims.getClaim("roles")).isNull();
        assertThat(accessClaims.getClaim("department")).isNull();
        assertThat(accessClaims.getClaim("title")).isNull();
        assertThat(accessClaims.getClaim("https://auth-study.local/claims/roles")).isNull();

        assertThat(jwtDecoder.decode(accessToken.serialize()).getSubject()).isEqualTo(expectedSubject);
        assertDecoderRejectsWrongIssuerAndAlgorithm(accessToken, active.kid());
    }

    private Discovery discovery() throws Exception {
        JsonNode json = getJson("/.well-known/openid-configuration");
        assertThat(json.path("issuer").asText()).isEqualTo(ISSUER);
        assertThat(toStrings(json.path("id_token_signing_alg_values_supported"))).contains("RS256");
        URI authorization = URI.create(json.path("authorization_endpoint").asText());
        URI token = URI.create(json.path("token_endpoint").asText());
        URI jwks = URI.create(json.path("jwks_uri").asText());
        assertThat(authorization.getScheme() + "://" + authorization.getAuthority()).isEqualTo(ISSUER);
        assertThat(token.getScheme() + "://" + token.getAuthority()).isEqualTo(ISSUER);
        assertThat(jwks.getScheme() + "://" + jwks.getAuthority()).isEqualTo(ISSUER);
        return new Discovery(authorization.getPath(), token.getPath(), jwks.getPath());
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String authorize(String authorizationPath, Fixture fixture, String nonce) throws Exception {
        String state = "state-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(get(authorizationPath)
                        .session(new MockHttpSession())
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid profile hr.roles")
                        .queryParam("state", state)
                        .queryParam("nonce", nonce)
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI location = URI.create(result.getResponse().getHeader("Location"));
        assertThat(query(location, "state")).isEqualTo(state);
        return query(location, "code");
    }

    private JsonNode exchange(String tokenPath, Fixture fixture, String code) throws Exception {
        MvcResult result = mockMvc.perform(post(tokenPath)
                        .header("Origin", ISSUER)
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", code)
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.path("id_token").asText()).isNotBlank();
        assertThat(response.path("access_token").asText()).isNotBlank();
        return response;
    }

    private void assertHeader(SignedJWT jwt, String kid) {
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(kid);
    }

    private void assertDecoderRejectsWrongIssuerAndAlgorithm(SignedJWT original, String activeKid)
            throws Exception {
        RSAKey activePrivate = jwkSource.get(
                        new JWKSelector(new JWKMatcher.Builder().keyID(activeKid).build()), null)
                .stream().map(key -> {
                    try {
                        return key.toRSAKey();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }).filter(RSAKey::isPrivate).findFirst().orElseThrow();

        JWTClaimsSet wrongIssuerClaims = new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                .issuer("http://attacker.invalid")
                .build();
        SignedJWT wrongIssuer = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(activeKid).build(), wrongIssuerClaims);
        wrongIssuer.sign(new RSASSASigner(activePrivate));
        assertThatThrownBy(() -> jwtDecoder.decode(wrongIssuer.serialize()))
                .isInstanceOf(JwtException.class);

        SignedJWT wrongAlgorithm = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(activeKid).build(), original.getJWTClaimsSet());
        wrongAlgorithm.sign(new RSASSASigner(activePrivate));
        assertThatThrownBy(() -> jwtDecoder.decode(wrongAlgorithm.serialize()))
                .isInstanceOf(JwtException.class);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "OIDC_" + suffix.toUpperCase();
        Instant now = Instant.now();
        long companyId = jdbcClient.sql("""
                insert into companies(code, name, email_domain, status, created_at, updated_at)
                values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                """).param("code", code).param("domain", code.toLowerCase() + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                """).param("companyId", companyId).param("now", Timestamp.from(now)).query(Long.class).single();
        long userId = jdbcClient.sql("""
                insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                  position_id, status, created_at, updated_at)
                values (:companyId, 'USER', :employeeNumber, 'OIDC User', '010-0000-0000', :hiredAt,
                        'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 24)).param("positionId", positionId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                insert into accounts(company_id, user_id, login_email, password_hash, status,
                                     must_change_password, created_at, updated_at)
                values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now) returning id
                """).param("companyId", companyId).param("userId", userId)
                .param("email", suffix + "@example.com").param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:accountId, 'USER')")
                .param("accountId", accountId).update();
        UUID subject = UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:accountId, :subject, :now)")
                .param("accountId", accountId).param("subject", subject).param("now", Timestamp.from(now)).update();
        String clientId = "oidc-client-" + suffix;
        long internalClientId = jdbcClient.sql("""
                insert into oauth_client(company_id, client_id, display_name, status, trust,
                                         public_client, created_at, updated_at)
                values (:companyId, :clientId, 'OIDC RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                        true, :now, :now) returning id
                """).param("companyId", companyId).param("clientId", clientId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                values (:clientId, :redirectUri, 'AUTHORIZATION')
                """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        for (String scope : Set.of("openid", "profile", "hr.roles")) {
            jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, :scope)")
                    .param("clientId", internalClientId).param("scope", scope).update();
        }
        return new Fixture(accountId, clientId);
    }

    private OAuthSigningKey generatedKey(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey privateJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .algorithm(JWSAlgorithm.RS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID(kid)
                    .build();
            String publicJwk = privateJwk.toPublicJWK().toJSONString();
            return OAuthSigningKey.active(kid, publicJwk,
                    cipher.encrypt(kid, "RS256", publicJwk,
                            privateJwk.toJSONString().getBytes(StandardCharsets.UTF_8)),
                    Instant.now());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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

    private static String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private record Discovery(String authorizationPath, String tokenPath, String jwksPath) { }
    private record Fixture(long accountId, String clientId) { }
}
