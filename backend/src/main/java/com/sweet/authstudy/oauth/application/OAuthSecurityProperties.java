package com.sweet.authstudy.oauth.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.oauth")
public record OAuthSecurityProperties(
        URI issuer,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        Duration idTokenTtl,
        Duration refreshTokenTtl,
        Duration sessionIdleTimeout,
        Duration sessionAbsoluteTimeout,
        String sessionCookieName,
        String userInfoAudience,
        String signingKeyWrappingKeyId,
        String signingKeyWrappingKeyBase64) {

    @ConstructorBinding
    public OAuthSecurityProperties {
    }

    public OAuthSecurityProperties(
            URI issuer,
            Duration authorizationCodeTtl,
            Duration accessTokenTtl,
            Duration idTokenTtl,
            Duration refreshTokenTtl,
            Duration sessionIdleTimeout,
            Duration sessionAbsoluteTimeout,
            String sessionCookieName,
            String userInfoAudience) {
        this(issuer, authorizationCodeTtl, accessTokenTtl, idTokenTtl, refreshTokenTtl,
                sessionIdleTimeout, sessionAbsoluteTimeout, sessionCookieName, userInfoAudience,
                null, null);
    }

    public Duration verificationKeyRetention() {
        return accessTokenTtl.compareTo(idTokenTtl) >= 0 ? accessTokenTtl : idTokenTtl;
    }
}
