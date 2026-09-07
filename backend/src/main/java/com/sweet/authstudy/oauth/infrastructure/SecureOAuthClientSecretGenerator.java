package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthClientSecretGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecureOAuthClientSecretGenerator implements OAuthClientSecretGenerator {

    private static final int CLIENT_ID_BYTES = 32;
    private static final int CLIENT_SECRET_BYTES = 48;

    private final SecureRandom secureRandom;

    public SecureOAuthClientSecretGenerator() {
        this(new SecureRandom());
    }

    SecureOAuthClientSecretGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String generateClientId() {
        return generate(CLIENT_ID_BYTES);
    }

    @Override
    public String generateClientSecret() {
        return generate(CLIENT_SECRET_BYTES);
    }

    private String generate(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
