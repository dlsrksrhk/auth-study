package com.sweet.authstudy.oauth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.oauth", name = {
        "signing-key-wrapping-key-id", "signing-key-wrapping-key-base64"
})
public class OAuthJwkSourceConfiguration {

    @Bean
    OAuthPrivateKeyCipher oauthPrivateKeyCipher(OAuthSecurityProperties properties) {
        return new OAuthPrivateKeyCipher(properties.signingKeyWrappingKeyId(),
                properties.signingKeyWrappingKeyBase64());
    }

    @Bean
    OAuthSigningKeySnapshotSource oauthSigningKeySnapshotSource(OAuthSigningKeyRepository repository,
            OAuthPrivateKeyCipher cipher) {
        return new OAuthSigningKeySnapshotSource(repository, cipher);
    }

    @Bean("oauthJwtEncoder")
    JwtEncoder oauthJwtEncoder(OAuthSigningKeySnapshotSource signingKeys) {
        return new OAuthSigningJwtEncoder(signingKeys);
    }

    @Bean
    JWKSource<SecurityContext> oauthJwkSource(OAuthSigningKeyRepository repository,
            OAuthPrivateKeyCipher cipher, OAuthSecurityProperties properties, Clock clock) {
        repository.bootstrapIfAbsent(() -> generate(cipher, clock.instant()));
        return publicJwkSource(repository, clock, properties.verificationKeyRetention());
    }

    static JWKSource<SecurityContext> publicJwkSource(OAuthSigningKeyRepository repository,
            Clock clock, Duration verificationRetention) {
        return (selector, context) -> {
            Instant cutoff = clock.instant().minus(verificationRetention);
            List<JWK> available = new ArrayList<>();
            available.add(publicJwk(repository.requireActive()));
            repository.findVerificationOnlyRetiredAfter(cutoff).stream()
                    .map(OAuthJwkSourceConfiguration::publicJwk)
                    .forEach(available::add);
            return selector.select(new JWKSet(available));
        };
    }

    @Bean("oauthJwtDecoder")
    @ConditionalOnMissingBean(name = "oauthJwtDecoder")
    JwtDecoder oauthJwtDecoder(JWKSource<SecurityContext> jwkSource,
            OAuthSecurityProperties properties) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        var issuer = JwtValidators.createDefaultWithIssuer(properties.issuer().toString());
        org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> algorithm = jwt ->
                "RS256".equals(jwt.getHeaders().get("alg"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                OAuth2ErrorCodes.INVALID_TOKEN,
                                "OAuth token validation failed.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, algorithm));
        return decoder;
    }

    private OAuthSigningKey generate(OAuthPrivateKeyCipher cipher, Instant activatedAt) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = UUID.randomUUID().toString();
            RSAKey privateJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .algorithm(JWSAlgorithm.RS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID(kid)
                    .build();
            String publicJwk = privateJwk.toPublicJWK().toJSONString();
            byte[] plaintext = privateJwk.toJSONString().getBytes(StandardCharsets.UTF_8);
            try {
                byte[] encrypted = cipher.encrypt(kid, OAuthSigningKey.RS256, publicJwk, plaintext);
                return OAuthSigningKey.active(kid, publicJwk, encrypted, activatedAt);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("OAuth signing key generation failed.", exception);
        }
    }

    static RSAKey publicJwk(OAuthSigningKey key) {
        try {
            RSAKey publicJwk = RSAKey.parse(key.publicJwk());
            if (publicJwk.isPrivate()
                    || !JWSAlgorithm.RS256.equals(publicJwk.getAlgorithm())
                    || !key.kid().equals(publicJwk.getKeyID())) {
                throw new IllegalStateException("OAuth public signing key is invalid.");
            }
            return publicJwk;
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException("OAuth public signing key is invalid.");
        }
    }
}
