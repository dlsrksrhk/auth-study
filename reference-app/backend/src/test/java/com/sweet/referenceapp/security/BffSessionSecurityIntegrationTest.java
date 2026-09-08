package com.sweet.referenceapp.security;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BffSessionSecurityIntegrationTest extends HttpSecurityTestSupport {
    @Test
    void successfulLoginDiscardsOldCsrfAndNewMaskedHeaderAllowsAuthenticatedMutation() throws Exception {
        var before = send("GET", "/bff/csrf", null, "");
        var oldToken = csrf(before).get("csrfToken").asText();
        var pending = begin(cookie(before));
        var success = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(success.headers().firstValue("Location")).contains(SPA + "/");
        var authenticatedCookie = cookie(success);
        assertThat(send("POST", "/bff/test", authenticatedCookie, "", "Origin", SPA, "X-CSRF-TOKEN", oldToken).statusCode()).isEqualTo(403);
        var after = send("GET", "/bff/csrf", authenticatedCookie, "");
        var newToken = csrf(after).get("csrfToken").asText();
        assertThat(send("POST", "/bff/test", authenticatedCookie, "", "Origin", SPA, "X-CSRF-TOKEN", newToken).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/bff/test", authenticatedCookie, "", "Origin", "https://attacker.example", "X-CSRF-TOKEN", newToken).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test", authenticatedCookie, "_csrf=" + newToken,
                "Origin", SPA, "Content-Type", "application/x-www-form-urlencoded").statusCode()).isEqualTo(403);
    }

    @Test
    void csrfExposesOnlyMaskedTokenAndHeaderWithPrivateSessionCookie() throws Exception {
        var first = send("GET", "/bff/csrf", null, "", "Origin", "https://attacker.example");
        assertThat(first.statusCode()).isEqualTo(200);
        var fields = new ArrayList<String>();
        csrf(first).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("csrfHeaderName", "csrfToken");
        assertThat(csrf(first).get("csrfHeaderName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(csrf(first).get("csrfToken").asText()).isNotBlank();
        var second = send("GET", "/bff/csrf", cookie(first), "");
        assertThat(csrf(second).get("csrfToken").asText()).isNotEqualTo(csrf(first).get("csrfToken").asText());
        assertSessionCookie(first);
        assertSecurityHeaders(first);
        assertThat(first.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void unsafeApiNeedsBothCsrfHeaderAndExactOriginBeforeAuthentication() throws Exception {
        var tokenResponse = send("GET", "/bff/csrf", null, "");
        assertThat(tokenResponse.statusCode()).isEqualTo(200);
        var token = csrf(tokenResponse).get("csrfToken").asText();
        var cookie = cookie(tokenResponse);
        assertThat(send("POST", "/bff/test", cookie, "", "Origin", SPA, "X-CSRF-TOKEN", token).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/bff/test", cookie, "", "Origin", SPA).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test", cookie, "", "X-CSRF-TOKEN", token).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff", cookie, "", "X-CSRF-TOKEN", token).statusCode()).isEqualTo(403);
        for (var origin : new String[]{"null", "https://127.0.0.1:3100", "http://localhost:3100",
                "http://127.0.0.1:3101", SPA + ", https://attacker.example", SPA + " https://attacker.example"}) {
            assertThat(send("POST", "/bff/test", cookie, "", "Origin", origin, "X-CSRF-TOKEN", token).statusCode()).isEqualTo(403);
        }
        assertThat(send("POST", "/bff/test", cookie, "", "Origin", SPA, "Origin", SPA, "X-CSRF-TOKEN", token).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test?_csrf=" + token, cookie, "", "Origin", SPA).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test", cookie, "_csrf=" + token,
                "Origin", SPA, "Content-Type", "application/x-www-form-urlencoded").statusCode()).isEqualTo(403);
        var other = send("GET", "/bff/csrf", null, "");
        assertThat(send("POST", "/bff/test", cookie(other), "", "Origin", SPA, "X-CSRF-TOKEN", token).statusCode()).isEqualTo(403);
    }
}
