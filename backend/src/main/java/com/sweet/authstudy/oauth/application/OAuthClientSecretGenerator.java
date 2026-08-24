package com.sweet.authstudy.oauth.application;

public interface OAuthClientSecretGenerator {

    String generateClientId();

    String generateClientSecret();
}
