package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReferenceLogoutIntegrationTest extends LocalLoginHttpTestSupport {
    @Autowired LogoutHandoffStore handoffs;

    @Test void appLogoutExpiresCookieAndRejectsOldSession() throws Exception {
        String cookie = login();
        var response = logout("/bff/logout", cookie);
        assertThat(response.statusCode()).isEqualTo(204);
        assertDeletion(response);
        assertPrivate(response);
        assertNoTokenLeak(response);
        assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(401);
        assertThat(ISSUER.revocationRequestCount()).isEqualTo(1);
        assertThat(ISSUER.revocationForm).containsEntry("token", "test-refresh-token");
    }

    @Test void fullLogoutReturnsOpaqueOneUseContinuationAndFixedRedirect() throws Exception {
        String cookie = login();
        var response = logout("/bff/logout/identity-provider?post_logout_redirect_uri=https://evil.example&client_id=evil&id_token_hint=evil", cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        assertDeletion(response);
        assertPrivate(response);
        assertNoTokenLeak(response);
        var json = JSON.readTree(response.body());
        assertThat(json.size()).isEqualTo(1);
        String continuation = json.path("continueUrl").asText();
        assertThat(continuation).matches("/bff/logout/continue/[A-Za-z0-9_-]{43}");
        assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(401);
        var redirect = send("GET", continuation, null, "");
        assertThat(redirect.statusCode()).isEqualTo(303);
        assertPrivate(redirect);
        var location = URI.create(redirect.headers().firstValue("Location").orElseThrow());
        assertThat(location.getScheme()).isEqualTo("http");
        assertThat(location.getHost()).isEqualTo("idp.localhost");
        assertThat(location.getPort()).isEqualTo(8080);
        assertThat(location.getPath()).isEqualTo("/connect/logout");
        var params = MockOidcIssuer.parameters(location.getRawQuery());
        assertThat(params.get("id_token_hint")).startsWith("eyJ");
        assertThat(params.get("client_id")).isEqualTo(MockOidcIssuer.CLIENT_ID);
        assertThat(params.get("post_logout_redirect_uri")).isEqualTo(SPA + "/logged-out");
        var reused = send("GET", continuation, null, "");
        assertThat(reused.statusCode()).isEqualTo(410);
        assertPrivate(reused);
        assertNoTokenLeak(reused);
    }

    @Test void exhaustedHandoffCapacityStillTerminatesLocalSession() throws Exception {
        var tickets = new java.util.ArrayList<String>();
        try {
            for (int i = 0; i < 1000; i++) tickets.add(handoffs.issue("fixture", "client"));
            String cookie = login();
            var response = logout("/bff/logout/identity-provider", cookie);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("logout_continuation_unavailable");
            assertDeletion(response);
            assertPrivate(response);
            assertNoTokenLeak(response);
            assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(401);
            assertThat(ISSUER.revocationRequestCount()).isEqualTo(1);
        } finally { tickets.forEach(handoffs::consume); }
    }
    @Test void failedRevocationStillCompletesLocalLogoutWithoutRetry() throws Exception {
        String cookie = login();
        ISSUER.fault = "revoke-error";
        var response = logout("/bff/logout", cookie);
        assertThat(response.statusCode()).isEqualTo(204);
        assertDeletion(response);
        assertPrivate(response);
        assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(401);
        assertThat(ISSUER.revocationRequestCount()).isEqualTo(1);
    }

    @Test void logoutRequiresCsrfOriginAndAuthentication() throws Exception {
        String cookie = login();
        for (String path : new String[]{"/bff/logout", "/bff/logout/identity-provider"}) {
            assertThat(send("POST", path, cookie, "", "Origin", SPA).statusCode()).isEqualTo(403);
            assertThat(send("POST", path, cookie, "", "Origin", "https://evil.example", "X-CSRF-TOKEN", csrfToken(cookie)).statusCode()).isEqualTo(403);
            var anonymous = send("GET", "/bff/csrf", null, "");
            assertThat(logout(path, cookie(anonymous)).statusCode()).isEqualTo(401);
        }
        assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(200);
        var malformed = send("GET", "/bff/logout/continue/malformed", null, "");
        assertThat(malformed.statusCode()).isEqualTo(410);
        assertPrivate(malformed);
    }
    static void assertPrivate(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
    }
}
