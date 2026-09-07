package com.sweet.authstudy.oauth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;

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
        Boolean sessionCookieSecure,
        String signingKeyWrappingKeyId,
        String signingKeyWrappingKeyBase64) {

    @ConstructorBinding
    public OAuthSecurityProperties {
        sessionCookieSecure = sessionCookieSecure == null ? Boolean.TRUE : sessionCookieSecure;
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
            String userInfoAudience,
            String signingKeyWrappingKeyId,
            String signingKeyWrappingKeyBase64) {
        this(issuer, authorizationCodeTtl, accessTokenTtl, idTokenTtl, refreshTokenTtl,
                sessionIdleTimeout, sessionAbsoluteTimeout, sessionCookieName, userInfoAudience,
                true, signingKeyWrappingKeyId, signingKeyWrappingKeyBase64);
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
                true, null, null);
    }

    public Duration verificationKeyRetention() {
        return accessTokenTtl.compareTo(idTokenTtl) >= 0 ? accessTokenTtl : idTokenTtl;
    }
}
