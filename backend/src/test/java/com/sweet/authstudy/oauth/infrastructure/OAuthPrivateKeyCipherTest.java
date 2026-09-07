package com.sweet.authstudy.oauth.infrastructure;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthPrivateKeyCipherTest {

    private static final String KEY_ID = "local-test-wrap-v1";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PUBLIC_JWK = "{\"kty\":\"RSA\",\"kid\":\"signing-1\"}";

    @Test
    void encrypt_uses_a_fresh_nonce_and_round_trips_only_in_the_same_context() {
        OAuthPrivateKeyCipher cipher = new OAuthPrivateKeyCipher(KEY_ID, KEY);
        byte[] plaintext = "{\"kty\":\"RSA\",\"d\":\"private\"}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.encrypt("signing-1", "RS256", PUBLIC_JWK, plaintext);
        byte[] second = cipher.encrypt("signing-1", "RS256", PUBLIC_JWK, plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.US_ASCII)).startsWith("v1.");
        assertThat(cipher.decrypt("signing-1", "RS256", PUBLIC_JWK, first))
                .containsExactly(plaintext);
        assertThatThrownBy(() -> cipher.decrypt("signing-2", "RS256", PUBLIC_JWK, first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");
        assertThatThrownBy(() -> cipher.decrypt("signing-1", "RS256", "{}", first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");
    }

    @Test
    void tampering_and_wrong_key_fail_with_the_same_generic_error() {
        OAuthPrivateKeyCipher cipher = new OAuthPrivateKeyCipher(KEY_ID, KEY);
        byte[] validEnvelope = cipher.encrypt("signing-1", "RS256", PUBLIC_JWK,
                "private".getBytes(StandardCharsets.UTF_8));
        String[] parts = new String(validEnvelope, StandardCharsets.US_ASCII).split("\\.");
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
        ciphertext[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        byte[] envelope = String.join(".", parts).getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> cipher.decrypt("signing-1", "RS256", PUBLIC_JWK, envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        OAuthPrivateKeyCipher wrong = new OAuthPrivateKeyCipher(
                KEY_ID, Base64.getEncoder().encodeToString(otherKey));
        byte[] valid = cipher.encrypt("signing-1", "RS256", PUBLIC_JWK,
                "private".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> wrong.decrypt("signing-1", "RS256", PUBLIC_JWK, valid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth signing key material could not be decrypted.");
    }

    @Test
    void wrapping_key_must_be_a_256_bit_aes_key() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new OAuthPrivateKeyCipher(KEY_ID, shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAuth signing wrapping key must decode to 32 bytes.");
    }
}
