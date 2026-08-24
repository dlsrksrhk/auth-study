package com.sweet.authstudy.oauth.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        String userInfoAudience) {
}
