package com.sweet.referenceapp.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import static org.assertj.core.api.Assertions.*;

class OAuthTokenRevokerTest {
    MockOidcIssuer issuer;
    @BeforeEach void start() { issuer = new MockOidcIssuer(); }
    @AfterEach void stop() { issuer.close(); }

    @Test void sendsOneBasicAuthenticatedRefreshTokenRevocation() {
        var revoker = new OAuthTokenRevoker(properties(Duration.ofSeconds(1)));
        revoker.revoke(registration("id:reserved", "s e/c?ret"), new OAuth2RefreshToken("refresh-secret", java.time.Instant.now()));
        assertThat(issuer.revocationRequestCount()).isEqualTo(1);
        assertThat(issuer.revocationForm).containsEntry("token", "refresh-secret").containsEntry("token_type_hint", "refresh_token");
        assertThat(issuer.clientAuthorization).isEqualTo("Basic " + Base64.getEncoder().encodeToString("id%3Areserved:s+e%2Fc%3Fret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void errorsRedirectsAndTimeoutsAreNotRetriedAndAreSuppressed() {
        var revoker = new OAuthTokenRevoker(properties(Duration.ofMillis(100)));
        for (var fault : new String[]{"revoke-error", "revoke-redirect", "revoke-delay"}) {
            issuer.reset(); issuer.fault = fault;
            assertThatCode(() -> revoker.revoke(registration("id", "secret"), token())).doesNotThrowAnyException();
            assertThat(issuer.revocationRequestCount()).isEqualTo(1);
        }
    }

    @Test void bodyStallCannotExceedWholeRevocationDeadline() {
        issuer.fault = "revoke-body-stall";
        var started = System.nanoTime();
        new OAuthTokenRevoker(properties(Duration.ofMillis(100))).revoke(registration("id", "secret"), token());
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(700);
        assertThat(issuer.revocationRequestCount()).isEqualTo(1);
    }

    private OAuthTokenLifecycleProperties properties(Duration deadline) { return new OAuthTokenLifecycleProperties(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), deadline, Duration.ofSeconds(60), 1000, URI.create(issuer.origin()+"/revoke"), URI.create(issuer.origin()+"/logout")); }
    private OAuth2RefreshToken token() { return new OAuth2RefreshToken("refresh-secret", java.time.Instant.now()); }
    private ClientRegistration registration(String id, String secret) { return ClientRegistration.withRegistrationId("reference-app").clientId(id).clientSecret(secret).clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://rp/callback").authorizationUri(issuer.origin()+"/authorize").tokenUri(issuer.origin()+"/token").userInfoUri(issuer.origin()+"/userinfo").userNameAttributeName("sub").clientName("test").build(); }
}
