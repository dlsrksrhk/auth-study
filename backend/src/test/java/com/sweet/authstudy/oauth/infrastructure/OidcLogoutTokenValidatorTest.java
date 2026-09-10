package com.sweet.authstudy.oauth.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import org.springframework.security.oauth2.jwt.JwtException;
import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class OidcLogoutTokenValidatorTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private final OidcLogoutTokenValidator validator = new OidcLogoutTokenValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void accepts_expired_token_with_ordered_timestamps() {
        assertThat(validator.validate(token(NOW.minusSeconds(7200), NOW.minusSeconds(3600), null)).hasErrors()).isFalse();
    }

    @Test
    void allows_exactly_sixty_seconds_of_future_clock_skew() {
        assertThat(validator.validate(token(NOW.plusSeconds(60), NOW.plusSeconds(300), NOW.plusSeconds(60))).hasErrors()).isFalse();
        assertThat(validator.validate(token(NOW.plusSeconds(61), NOW.plusSeconds(300), null)).hasErrors()).isTrue();
        assertThat(validator.validate(token(NOW, NOW.plusSeconds(300), NOW.plusSeconds(61))).hasErrors()).isTrue();
    }

    @Test
    void rejects_missing_or_malformed_time_claims() {
        for (String claim : new String[]{"iat", "exp"}) {
            Jwt missing = Jwt.withTokenValue("hint").header("alg", "RS256")
                    .claim("sub", "subject").claims(c -> c.put(claim.equals("iat") ? "exp" : "iat", NOW)).build();
            assertThat(validator.validate(missing).hasErrors()).as("missing %s", claim).isTrue();
        }
        for (String claim : new String[]{"iat", "exp", "nbf"}) {
            var claims = new HashMap<String, Object>();
            claims.put("iat", NOW);
            claims.put("exp", NOW.plusSeconds(300));
            claims.put(claim, "not-a-time");
            Jwt malformed = new Jwt("hint", null, null, Map.of("alg", "RS256"), claims);
            assertThat(validator.validate(malformed).hasErrors()).as("malformed %s", claim).isTrue();
        }
    }

    @Test
    void rejects_expiry_equal_to_or_before_issue_time() {
        // Jwt's constructor also rejects reversed timestamps; raw claims exercise our validator.
        for (Instant expiry : new Instant[]{NOW, NOW.minusSeconds(1)}) {
            Jwt jwt = new Jwt("hint", null, null, Map.of("alg", "RS256"),
                    Map.of("iat", NOW, "exp", expiry));
            assertThat(validator.validate(jwt).hasErrors()).isTrue();
        }
    }

    @Test
    void logout_decoder_verifies_signature_and_rs256_even_when_expiry_is_ignored() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        var jwks = new ImmutableJWKSet<SecurityContext>(
                new JWKSet(key.toPublicJWK()));
        var properties = new OAuthSecurityProperties(
                URI.create("https://issuer.example"), null, null, null, null, null, null, null, null);
        var decoder = new OAuthJwkSourceConfiguration().oauthLogoutJwtDecoder(jwks, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        var claims = new JWTClaimsSet.Builder().issuer("https://issuer.example")
                .subject("subject").issueTime(Date.from(NOW.minusSeconds(7200)))
                .expirationTime(Date.from(NOW.minusSeconds(3600))).build();
        var valid = signed(key, JWSAlgorithm.RS256, claims);
        assertThat(decoder.decode(valid).getSubject()).isEqualTo("subject");
        var wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        for (String invalid : new String[]{
                signed(wrongKey, JWSAlgorithm.RS256, claims),
                signed(key, JWSAlgorithm.RS512, claims),
                signed(key, JWSAlgorithm.RS256,
                        new JWTClaimsSet.Builder(claims).issuer("https://wrong.example").build())}) {
            assertThatThrownBy(() -> decoder.decode(invalid))
                    .isInstanceOf(JwtException.class);
        }
    }

    private String signed(RSAKey key, JWSAlgorithm algorithm,
                          JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private Jwt token(Instant issuedAt, Instant expiresAt, Instant notBefore) {
        var builder = Jwt.withTokenValue("hint").header("alg", "RS256").issuedAt(issuedAt).expiresAt(expiresAt);
        if (notBefore != null) builder.notBefore(notBefore);
        return builder.build();
    }
}
