package com.sweet.authstudy.oauth.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class OAuthPrivateKeyCipher {

    private static final String VERSION = "v1";
    private static final String CONTEXT = "auth-study/oauth-signing-key/private-jwk/v1";
    private static final String FAILURE = "OAuth signing key material could not be decrypted.";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String wrappingKeyId;
    private final SecretKeySpec wrappingKey;
    private final SecureRandom secureRandom;

    public OAuthPrivateKeyCipher(String wrappingKeyId, String wrappingKeyBase64) {
        this(wrappingKeyId, wrappingKeyBase64, new SecureRandom());
    }

    OAuthPrivateKeyCipher(String wrappingKeyId, String wrappingKeyBase64, SecureRandom secureRandom) {
        if (wrappingKeyId == null || wrappingKeyId.isBlank()) {
            throw new IllegalArgumentException("OAuth signing wrapping key id must not be blank.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(wrappingKeyBase64);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("OAuth signing wrapping key must be valid Base64.", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("OAuth signing wrapping key must decode to 32 bytes.");
        }
        this.wrappingKeyId = wrappingKeyId;
        this.wrappingKey = new SecretKeySpec(Arrays.copyOf(decoded, decoded.length), "AES");
        Arrays.fill(decoded, (byte) 0);
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public byte[] encrypt(String kid, String algorithm, String publicJwk, byte[] privateJwk) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(kid, algorithm, publicJwk));
            byte[] ciphertext = cipher.doFinal(privateJwk);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String envelope = VERSION + "." + encoder.encodeToString(
                    wrappingKeyId.getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(nonce) + "." + encoder.encodeToString(ciphertext);
            return envelope.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OAuth signing key material could not be encrypted.", exception);
        }
    }

    public byte[] decrypt(String kid, String algorithm, String publicJwk, byte[] envelope) {
        try {
            String[] parts = new String(envelope, StandardCharsets.US_ASCII).split("\\.", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) throw new IllegalArgumentException();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String envelopeKeyId = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
            if (!wrappingKeyId.equals(envelopeKeyId)) throw new IllegalArgumentException();
            byte[] nonce = decoder.decode(parts[2]);
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException();
            byte[] ciphertext = decoder.decode(parts[3]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(kid, algorithm, publicJwk));
            return cipher.doFinal(ciphertext);
        } catch (Exception exception) {
            throw new IllegalStateException(FAILURE);
        }
    }

    private byte[] aad(String kid, String algorithm, String publicJwk) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, CONTEXT);
                write(output, wrappingKeyId);
                write(output, kid);
                write(output, algorithm);
                write(output, publicJwk);
            }
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void write(DataOutputStream output, String value) throws java.io.IOException {
        byte[] encoded = java.util.Objects.requireNonNull(value, "authenticated context")
                .getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
