package com.sweet.authstudy.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        Jwt jwt,
        RefreshCookie refreshCookie,
        LoginLock loginLock,
        BootstrapAdmin bootstrapAdmin,
        String browserOrigin) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record RefreshCookie(String name, String path, boolean httpOnly, boolean secure, String sameSite) {
    }

    public record LoginLock(int maxFailures, Duration lockDuration) {
    }

    public record BootstrapAdmin(String email, String password, boolean mustChangePassword) {
    }
}
