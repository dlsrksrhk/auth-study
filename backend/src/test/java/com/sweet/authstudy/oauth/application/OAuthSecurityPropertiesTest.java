package com.sweet.authstudy.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class OAuthSecurityPropertiesTest {

    @Test
    void session_cookie_is_secure_by_default_but_can_be_disabled_for_local_profiles() {
        OAuthSecurityProperties safeDefault = new OAuthSecurityProperties(
                URI.create("https://idp.example"), Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofMinutes(30),
                Duration.ofHours(8), "IDP_AUTH_SESSION", "userinfo");
        OAuthSecurityProperties local = new OAuthSecurityProperties(
                URI.create("http://idp.localhost:8080"), Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofMinutes(30),
                Duration.ofHours(8), "IDP_AUTH_SESSION", "userinfo", false, null, null);

        assertThat(safeDefault.sessionCookieSecure()).isTrue();
        assertThat(local.sessionCookieSecure()).isFalse();
    }
}
