package com.sweet.authstudy.oauth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
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
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;

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
    JWKSource<SecurityContext> oauthJwkSource(OAuthSigningKeyRepository repository,
            OAuthPrivateKeyCipher cipher, OAuthSecurityProperties properties, Clock clock) {
        repository.bootstrapIfAbsent(() -> generate(cipher, clock.instant()));
        return (selector, context) -> {
            Instant cutoff = clock.instant().minus(properties.verificationKeyRetention());
            List<JWK> available = new ArrayList<>();
            repository.findActive().ifPresent(key -> available.add(privateJwk(key, cipher)));
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
        NimbusJwtDecoder decoder = (NimbusJwtDecoder)
                OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
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
            byte[] encrypted = cipher.encrypt(kid, OAuthSigningKey.RS256, publicJwk,
                    privateJwk.toJSONString().getBytes(StandardCharsets.UTF_8));
            return OAuthSigningKey.active(kid, publicJwk, encrypted, activatedAt);
        } catch (Exception exception) {
            throw new IllegalStateException("OAuth signing key generation failed.", exception);
        }
    }

    private static RSAKey privateJwk(OAuthSigningKey key, OAuthPrivateKeyCipher cipher) {
        try {
            byte[] decrypted = cipher.decrypt(key.kid(), key.algorithm(), key.publicJwk(),
                    key.encryptedPrivateMaterial());
            RSAKey privateJwk = RSAKey.parse(new String(decrypted, StandardCharsets.UTF_8));
            RSAKey publicJwk = publicJwk(key);
            if (!privateJwk.isPrivate()
                    || !JWSAlgorithm.RS256.equals(privateJwk.getAlgorithm())
                    || !key.kid().equals(privateJwk.getKeyID())
                    || !privateJwk.toPublicJWK().equals(publicJwk)) {
                throw new IllegalStateException("OAuth signing key material could not be decrypted.");
            }
            return privateJwk;
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException("OAuth signing key material could not be decrypted.");
        }
    }

    private static RSAKey publicJwk(OAuthSigningKey key) {
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
