package com.sweet.authstudy.oauth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;

final class OAuthSigningKeySnapshotSource {

    private static final String FAILURE = "OAuth signing key material could not be decrypted.";

    private final OAuthSigningKeyRepository repository;
    private final OAuthPrivateKeyCipher cipher;

    OAuthSigningKeySnapshotSource(OAuthSigningKeyRepository repository, OAuthPrivateKeyCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    OAuthSigningKeySnapshot requireActive() {
        OAuthSigningKey key = repository.requireActive();
        byte[] decrypted = null;
        try {
            decrypted = cipher.decrypt(key.kid(), key.algorithm(), key.publicJwk(),
                    key.encryptedPrivateMaterial());
            RSAKey privateJwk = RSAKey.parse(new String(decrypted, StandardCharsets.UTF_8));
            RSAKey publicJwk = OAuthJwkSourceConfiguration.publicJwk(key);
            if (!privateJwk.isPrivate()
                    || !JWSAlgorithm.RS256.equals(privateJwk.getAlgorithm())
                    || !key.kid().equals(privateJwk.getKeyID())
                    || !privateJwk.toPublicJWK().equals(publicJwk)) {
                throw new IllegalStateException(FAILURE);
            }
            return new OAuthSigningKeySnapshot(key.kid(), privateJwk);
        } catch (Exception exception) {
            throw new IllegalStateException(FAILURE);
        } finally {
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
        }
    }
}
