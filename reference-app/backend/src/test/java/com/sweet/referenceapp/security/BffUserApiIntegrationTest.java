package com.sweet.referenceapp.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BffUserApiIntegrationTest extends HttpSecurityTestSupport {
    @Test
    void anonymousSessionDoesNotCreateSessionAndProfileReturnsUnauthorized() throws Exception {
        var session = send("GET", "/bff/session", null, "");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(session.body())).isEqualTo(JSON.readTree("{\"authenticated\":false}"));
        assertThat(session.headers().allValues("Set-Cookie")).isEmpty();
        assertSecurityHeaders(session);
        assertNoTokenLeak(session);
        var profile = send("GET", "/bff/profile", null, "");
        assertThat(profile.statusCode()).isEqualTo(401);
        assertThat(profile.headers().firstValue("Location")).isEmpty();
        assertThat(profile.headers().allValues("Set-Cookie")).isEmpty();
        assertSecurityHeaders(profile);
        assertNoTokenLeak(profile);
        verifyNoInteractions(currentUser, localLogin);
    }

    @Test
    void authenticatedApisExposeOnlyAllowedLatestFieldsAndReuseOneLookupPerRequest() throws Exception {
        var cookie = login();
        var original = currentUser.find(java.util.UUID.randomUUID()).orElseThrow();
        var snapshot = new ExternalUserSnapshot("latest@example.com", "최신 사용자",
                Map.of("code", "ACME", "name", "회사"),
                Map.of("primary_department", Map.of("code", "ENG", "name", "개발")),
                Set.of("Z_HR", "A_HR"));
        var latest = new AppUserView(original.id(), original.issuer(), original.subject(), snapshot,
                original.status(), Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), original.createdAt(),
                original.updatedAt(), original.lastLoginAt(), original.version());
        when(currentUser.find(latest.id())).thenReturn(Optional.of(latest));
        clearInvocations(currentUser, localLogin);
        int userInfoRequests = ISSUER.userInfoRequestCount();

        var response = send("GET", "/bff/session", cookie, "");
        assertThat(response.statusCode()).isEqualTo(200);
        var session = JSON.readTree(response.body());
        assertFields(session, "authenticated", "user", "csrfHeaderName", "csrfToken");
        assertThat(session.get("authenticated").asBoolean()).isTrue();
        assertFields(session.get("user"), "id", "displayName", "email", "status", "roles");
        assertThat(session.get("user").get("id").asText()).isEqualTo(latest.id().toString());
        assertThat(session.get("user").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(session.get("user").get("displayName").asText()).isEqualTo(snapshot.displayName());
        assertThat(session.get("user").get("email").asText()).isEqualTo(snapshot.email());
        assertThat(session.get("user").get("roles")).isEqualTo(JSON.readTree("[\"APP_ADMIN\",\"APP_USER\"]"));
        assertThat(session.get("csrfHeaderName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(session.get("csrfToken").asText()).isNotBlank();
        assertSecurityHeaders(response);
        assertNoTokenLeak(response);
        verify(currentUser).find(latest.id());
        verifyNoMoreInteractions(currentUser);
        clearInvocations(currentUser);

        var profileResponse = send("GET", "/bff/profile", cookie, "");
        assertThat(profileResponse.statusCode()).isEqualTo(200);
        var profile = JSON.readTree(profileResponse.body());
        assertFields(profile, "displayName", "email", "company", "organization", "hrRoles", "roles");
        assertThat(profile.get("displayName").asText()).isEqualTo(snapshot.displayName());
        assertThat(profile.get("email").asText()).isEqualTo(snapshot.email());
        assertThat(profile.get("company")).isEqualTo(JSON.valueToTree(snapshot.company()));
        assertThat(profile.get("organization")).isEqualTo(JSON.valueToTree(snapshot.organization()));
        assertThat(profile.get("hrRoles")).isEqualTo(JSON.readTree("[\"A_HR\",\"Z_HR\"]"));
        assertThat(profile.get("roles")).isEqualTo(session.get("user").get("roles"));
        assertSecurityHeaders(profileResponse);
        assertNoTokenLeak(profileResponse);
        verify(currentUser).find(latest.id());
        verifyNoMoreInteractions(currentUser);
        verifyNoInteractions(localLogin);
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(userInfoRequests);
    }

    @Test
    void absentOptionalSnapshotFieldsAndEmptyRolesRemainNullAndEmptyArrays() throws Exception {
        var cookie = login();
        var original = currentUser.find(java.util.UUID.randomUUID()).orElseThrow();
        when(currentUser.find(original.id())).thenReturn(Optional.of(new AppUserView(original.id(), original.issuer(),
                original.subject(), new ExternalUserSnapshot(null, null, null, null, Set.of()), original.status(),
                Set.of(), original.createdAt(), original.updatedAt(), original.lastLoginAt(), original.version())));
        var sessionResponse = send("GET", "/bff/session", cookie, "");
        assertThat(sessionResponse.statusCode()).isEqualTo(200);
        var session = JSON.readTree(sessionResponse.body());
        assertThat(session.get("user").get("displayName").isNull()).isTrue();
        assertThat(session.get("user").get("email").isNull()).isTrue();
        assertThat(session.get("user").get("roles")).isEqualTo(JSON.readTree("[]"));
        var profileResponse = send("GET", "/bff/profile", cookie, "");
        assertThat(profileResponse.statusCode()).isEqualTo(200);
        var profile = JSON.readTree(profileResponse.body());
        assertFields(profile, "displayName", "email", "company", "organization", "hrRoles", "roles");
        for (var field : new String[]{"displayName", "email", "company", "organization"}) {
            assertThat(profile.get(field).isNull()).isTrue();
        }
        assertThat(profile.get("hrRoles")).isEqualTo(JSON.readTree("[]"));
        assertThat(profile.get("roles")).isEqualTo(JSON.readTree("[]"));
    }

    @Test
    void sessionMaskedCsrfAllowsMutationAndRejectsPreLoginToken() throws Exception {
        var before = send("GET", "/bff/csrf", null, "");
        assertSecurityHeaders(before);
        var oldToken = csrf(before).get("csrfToken").asText();
        var pending = begin(cookie(before));
        var success = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(success.headers().firstValue("Location")).contains(SPA + "/");
        assertNoTokenLeak(success);
        var cookie = cookie(success);
        var response = send("GET", "/bff/session", cookie, "");
        assertThat(response.statusCode()).isEqualTo(200);
        var session = JSON.readTree(response.body());
        var header = session.get("csrfHeaderName").asText();
        var token = session.get("csrfToken").asText();
        var second = JSON.readTree(send("GET", "/bff/session", cookie, "").body());
        assertThat(second.get("csrfToken").asText()).isNotEqualTo(token);
        var accepted = send("POST", "/bff/test", cookie, "", "Origin", SPA, header, token);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.body()).get("mutated").asBoolean()).isTrue();
        var rejected = send("POST", "/bff/test", cookie, "", "Origin", SPA, header, oldToken);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test", cookie, "", "Origin", "https://attacker.example", header, token)
                .statusCode()).isEqualTo(403);
        assertNoTokenLeak(response);
        assertNoTokenLeak(accepted);
        assertNoTokenLeak(rejected);
    }

    private static void assertFields(JsonNode value, String... expected) {
        var fields = new HashSet<String>();
        value.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(expected);
    }
}
