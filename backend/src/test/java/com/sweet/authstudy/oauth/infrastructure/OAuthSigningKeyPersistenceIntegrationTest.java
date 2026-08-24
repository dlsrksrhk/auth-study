package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OAuthSigningKeyPersistenceIntegrationTest {

    @Autowired private JdbcClient jdbcClient;
    @Autowired private OAuthSigningKeyRepository keys;
    @Autowired private OAuthPrivateKeyCipher cipher;
    @Autowired private JWKSource<SecurityContext> jwkSource;
    @Autowired private OAuthSecurityProperties oauthProperties;
    @Autowired private AppSecurityProperties appSecurityProperties;

    @Test
    @Order(1)
    void migration_has_key_and_protocol_event_constraints_without_raw_credentials() {
        List<String> keyColumns = columns("oauth_signing_key");
        assertThat(keyColumns).contains("kid", "algorithm", "encrypted_private_material", "public_jwk",
                "status", "activated_at", "retired_at");

        List<String> eventColumns = columns("oauth_protocol_event");
        assertThat(eventColumns).contains("occurred_at", "correlation_id", "event_type", "outcome",
                "client_id", "subject", "account_id", "company_id", "error_code", "metadata");
        assertThat(eventColumns).noneMatch(name -> name.matches(
                ".*(secret|password|code_value|token_value|private_key|verifier|cookie|request_body).*"));

        List<String> eventIndexes = jdbcClient.sql("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'oauth_protocol_event'
                order by indexname
                """).query(String.class).list();
        assertThat(eventIndexes).anyMatch(name -> name.contains("occurred"))
                .anyMatch(name -> name.contains("correlation"))
                .anyMatch(name -> name.contains("client"))
                .anyMatch(name -> name.contains("subject"));
    }

    @Test
    @Order(2)
    void bootstrap_persists_only_encrypted_private_jwk_and_public_jwks_material() throws Exception {
        OAuthSigningKey active = keys.findActive().orElseThrow();
        byte[] stored = jdbcClient.sql("""
                select encrypted_private_material from oauth_signing_key where kid = :kid
                """).param("kid", active.kid()).query(byte[].class).single();
        String publicJwk = jdbcClient.sql("""
                select public_jwk::text from oauth_signing_key where kid = :kid
                """).param("kid", active.kid()).query(String.class).single();

        assertThat(new String(stored, StandardCharsets.US_ASCII))
                .startsWith("v1.")
                .doesNotContain("\"d\"", "\"p\"", "\"q\"");
        RSAKey publicKey = RSAKey.parse(publicJwk);
        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(publicKey.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);

        byte[] decrypted = cipher.decrypt(active.kid(), active.algorithm(), active.publicJwk(), stored);
        RSAKey privateKey = RSAKey.parse(new String(decrypted, StandardCharsets.UTF_8));
        assertThat(privateKey.isPrivate()).isTrue();
        assertThat(privateKey.toPublicJWK()).isEqualTo(publicKey);
        assertThat(oauthProperties.signingKeyWrappingKeyBase64())
                .isNotEqualTo(appSecurityProperties.jwt().secret());
        OAuthPrivateKeyCipher hrSecretCipher = new OAuthPrivateKeyCipher(
                oauthProperties.signingKeyWrappingKeyId(), appSecurityProperties.jwt().secret());
        assertThatThrownBy(() -> hrSecretCipher.decrypt(
                active.kid(), active.algorithm(), active.publicJwk(), stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");
    }

    @Test
    @Order(3)
    void database_rejects_a_second_active_key() {
        OAuthSigningKey active = keys.findActive().orElseThrow();
        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into oauth_signing_key(
                    kid, algorithm, encrypted_private_material, public_jwk, status, activated_at)
                values (:kid, 'RS256', :encrypted, cast(:publicJwk as jsonb), 'ACTIVE', :activatedAt)
                """).param("kid", "duplicate-active-" + UUID.randomUUID())
                .param("encrypted", active.encryptedPrivateMaterial())
                .param("publicJwk", active.publicJwk())
                .param("activatedAt", Timestamp.from(Instant.now())).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(activeCount()).isEqualTo(1L);
    }

    @Test
    @Order(4)
    void concurrent_bootstrap_is_idempotent_and_produces_exactly_one_active_key() throws Exception {
        jdbcClient.sql("delete from oauth_signing_key").update();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<OAuthSigningKey>> calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> (Callable<OAuthSigningKey>) () ->
                            keys.bootstrapIfAbsent(() -> generatedKey("bootstrap-" + index)))
                    .toList();
            var futures = executor.invokeAll(calls, 30, TimeUnit.SECONDS);
            List<OAuthSigningKey> winners = futures.stream().map(future -> {
                try {
                    return future.get(1, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(winners).extracting(OAuthSigningKey::kid).containsOnly(winners.getFirst().kid());
            assertThat(activeCount()).isEqualTo(1L);
        }
    }

    @Test
    @Order(5)
    void rotation_keeps_recent_public_verification_key_and_excludes_expired_one() throws Exception {
        OAuthSigningKey previous = keys.findActive().orElseThrow();
        Instant rotationTime = Instant.now();
        OAuthSigningKey active = keys.rotate(() -> generatedKey("rotated-" + UUID.randomUUID()), rotationTime);

        List<com.nimbusds.jose.jwk.JWK> afterRotation = jwkSource.get(
                new JWKSelector(new JWKMatcher.Builder().build()), null);
        assertThat(afterRotation).extracting(com.nimbusds.jose.jwk.JWK::getKeyID)
                .contains(previous.kid(), active.kid());
        assertThat(afterRotation.stream().filter(key -> key.getKeyID().equals(active.kid())).findFirst().orElseThrow()
                .isPrivate()).isTrue();
        assertThat(afterRotation.stream().filter(key -> key.getKeyID().equals(previous.kid())).findFirst().orElseThrow()
                .isPrivate()).isFalse();
        assertThat(activeCount()).isEqualTo(1L);

        jdbcClient.sql("""
                update oauth_signing_key
                set activated_at = :activatedAt, retired_at = :retiredAt where kid = :kid
                """)
                .param("activatedAt", Timestamp.from(rotationTime.minus(Duration.ofMinutes(10))))
                .param("retiredAt", Timestamp.from(rotationTime.minus(Duration.ofMinutes(6))))
                .param("kid", previous.kid()).update();
        List<com.nimbusds.jose.jwk.JWK> afterExpiry = jwkSource.get(
                new JWKSelector(new JWKMatcher.Builder().build()), null);
        assertThat(afterExpiry).extracting(com.nimbusds.jose.jwk.JWK::getKeyID)
                .contains(active.kid()).doesNotContain(previous.kid());
    }

    private List<String> columns(String table) {
        return jdbcClient.sql("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = :table
                order by ordinal_position
                """).param("table", table).query(String.class).list();
    }

    private long activeCount() {
        return jdbcClient.sql("select count(*) from oauth_signing_key where status = 'ACTIVE'")
                .query(Long.class).single();
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
            byte[] encrypted = cipher.encrypt(kid, "RS256", publicJwk,
                    privateJwk.toJSONString().getBytes(StandardCharsets.UTF_8));
            return OAuthSigningKey.active(kid, publicJwk, encrypted, Instant.now());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
