package com.sweet.authstudy.oauth.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthSigningKeySourceTest {

    private OAuthSigningKeyRepository repository;
    private OAuthPrivateKeyCipher cipher;
    private OAuthSigningKey key;
    private byte[] privateJson;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(OAuthSigningKeyRepository.class);
        cipher = new OAuthPrivateKeyCipher("test-wrap",
                Base64.getEncoder().encodeToString(new byte[32]));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey privateJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID("active-kid")
                .build();
        String publicJwk = privateJwk.toPublicJWK().toJSONString();
        privateJson = privateJwk.toJSONString().getBytes(StandardCharsets.UTF_8);
        key = OAuthSigningKey.active("active-kid", publicJwk,
                cipher.encrypt("active-kid", "RS256", publicJwk, privateJson), Instant.now());
        when(repository.requireActive()).thenReturn(key);
        when(repository.findVerificationOnlyRetiredAfter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    @Test
    void signing_snapshot_binds_the_active_kid_to_its_exact_private_key() {
        OAuthSigningKeySnapshotSource source = new OAuthSigningKeySnapshotSource(repository, cipher);

        OAuthSigningKeySnapshot snapshot = source.requireActive();

        assertThat(snapshot.kid()).isEqualTo("active-kid");
        assertThat(snapshot.privateJwk().isPrivate()).isTrue();
        assertThat(snapshot.privateJwk().toPublicJWK().toJSONString()).isEqualTo(key.publicJwk());
    }

    @Test
    void decrypted_plaintext_bytes_are_wiped_even_when_private_jwk_is_corrupt() {
        byte[] corruptPlaintext = "not-a-jwk".getBytes(StandardCharsets.UTF_8);
        OAuthPrivateKeyCipher decryptor = mock(OAuthPrivateKeyCipher.class);
        when(decryptor.decrypt(key.kid(), key.algorithm(), key.publicJwk(), key.encryptedPrivateMaterial()))
                .thenReturn(corruptPlaintext);
        OAuthSigningKeySnapshotSource source = new OAuthSigningKeySnapshotSource(repository, decryptor);

        assertThatThrownBy(source::requireActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");
        assertThat(corruptPlaintext).containsOnly((byte) 0);
    }

    @Test
    void public_source_never_returns_private_material() throws Exception {
        JWKSource<SecurityContext> source = OAuthJwkSourceConfiguration.publicJwkSource(
                repository, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(5));

        var selected = source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);

        assertThat(selected).extracting(com.nimbusds.jose.jwk.JWK::getKeyID).containsExactly("active-kid");
        assertThat(selected).allMatch(jwk -> !jwk.isPrivate());
    }

    @Test
    void missing_active_key_is_not_hidden_by_the_signing_or_public_source() throws Exception {
        when(repository.requireActive()).thenThrow(
                new IllegalStateException("Exactly one active OAuth signing key is required."));
        OAuthSigningKeySnapshotSource signing = new OAuthSigningKeySnapshotSource(repository, cipher);
        JWKSource<SecurityContext> verification = OAuthJwkSourceConfiguration.publicJwkSource(
                repository, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(5));

        assertThatThrownBy(signing::requireActive).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verification.get(
                new JWKSelector(new JWKMatcher.Builder().build()), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encoder_replaces_a_stale_header_kid_with_the_same_snapshot_key_it_signs_with() throws Exception {
        OAuthSigningJwtEncoder encoder = new OAuthSigningJwtEncoder(
                new OAuthSigningKeySnapshotSource(repository, cipher));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://idp.localhost:8080")
                .subject("opaque-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId("stale-kid").build(), claims))
                .getTokenValue();

        SignedJWT signed = SignedJWT.parse(token);
        assertThat(signed.getHeader().getKeyID()).isEqualTo("active-kid");
        assertThat(signed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(signed.verify(new RSASSAVerifier(RSAKey.parse(key.publicJwk())))).isTrue();
    }
}
