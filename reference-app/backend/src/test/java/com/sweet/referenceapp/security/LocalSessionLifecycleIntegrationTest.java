package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalSessionLifecycleIntegrationTest extends LocalLoginHttpTestSupport {
    @Test
    void roleGrantAndRevocationAffectNextRequestWithoutLoginWritesOrIdpCalls() throws Exception {
        var cookie = login();
        var id = userId(cookie);
        var row = jdbc.queryForMap("select * from app_user where id=?", id);
        int calls = ISSUER.userInfoRequestCount();
        assertThat(send("GET", "/bff/test-admin", cookie, "").statusCode()).isEqualTo(403);
        jdbc.update("insert into app_user_role values (?, 'APP_ADMIN')", id);
        assertThat(send("GET", "/bff/test-admin", cookie, "").statusCode()).isEqualTo(200);
        assertThat(
                        JSON.readTree(send("GET", "/bff/profile", cookie, "").body())
                                .path("roles")
                                .toString())
                .isEqualTo("[\"APP_ADMIN\",\"APP_USER\"]");
        jdbc.update("delete from app_user_role where app_user_id=? and role='APP_ADMIN'", id);
        assertThat(send("GET", "/bff/test-admin", cookie, "").statusCode()).isEqualTo(403);
        assertThat(
                        JSON.readTree(send("GET", "/bff/profile", cookie, "").body())
                                .path("roles")
                                .toString())
                .isEqualTo("[\"APP_USER\"]");
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(calls);
        assertThat(jdbc.queryForMap("select * from app_user where id=?", id)).isEqualTo(row);
    }

    @Test
    void revokingAdministratorFromLoginPrincipalDeniesNextRequest() throws Exception {
        ISSUER.userInfoClaims =
                java.util.Map.of(
                        "sub", ISSUER.subject, HR_ROLES, java.util.List.of("COMPANY_ADMIN"));
        var cookie = login();
        var id = userId(cookie);
        assertThat(send("GET", "/bff/test-admin", cookie, "").statusCode()).isEqualTo(200);
        jdbc.update("delete from app_user_role where app_user_id=? and role='APP_ADMIN'", id);
        assertThat(send("GET", "/bff/test-admin", cookie, "").statusCode()).isEqualTo(403);
        assertThat(
                        JSON.readTree(send("GET", "/bff/profile", cookie, "").body())
                                .path("roles")
                                .toString())
                .isEqualTo("[\"APP_USER\"]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "DELETED"})
    void stateChangeInvalidatesBothIndependentSessionsWithApiSpecificResponses(String state)
            throws Exception {
        var first = login();
        var second = login();
        var id = userId(first);
        int calls = ISSUER.userInfoRequestCount();
        var lastLogin =
                jdbc.queryForObject(
                        "select last_login_at from app_user where id=?",
                        java.time.OffsetDateTime.class,
                        id);
        if (state.equals("DISABLED"))
            jdbc.update("update app_user set status='DISABLED' where id=?", id);
        else jdbc.update("delete from app_user where id=?", id);
        var session = send("GET", "/bff/session", first, "");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.body()).isEqualTo("{\"authenticated\":false}");
        assertDeletion(session);
        var profile = send("GET", "/bff/profile", second, "");
        assertThat(profile.statusCode()).isEqualTo(401);
        assertDeletion(profile);
        assertThat(send("GET", "/bff/profile", first, "").statusCode()).isEqualTo(401);
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(calls);
        if (state.equals("DISABLED"))
            assertThat(
                            jdbc.queryForObject(
                                    "select last_login_at from app_user where id=?",
                                    java.time.OffsetDateTime.class,
                                    id))
                    .isEqualTo(lastLogin);
    }

    @Test
    void authorizedClientsStayIndependentAndFailureRemovesOnlyAffectedSession() throws Exception {
        var first = login();
        var second = login();
        assertThat(first).isNotEqualTo(second);
        for (var entry : java.util.Map.of(first, 1, second, 2).entrySet()) {
            var response =
                    send(
                            "GET",
                            "/bff/test-client?exchange=" + entry.getValue(),
                            entry.getKey(),
                            "");
            assertThat(response.body()).isEqualTo("{\"matchesExpectedClient\":true}");
            assertNoTokenLeak(response);
        }
        var pending = begin(first);
        assertFailure(
                callback(pending, "error=access_denied&state=" + pending.state()),
                first,
                "oidc_login_failed");
        assertThat(send("GET", "/bff/test-client?exchange=2", second, "").body())
                .isEqualTo("{\"matchesExpectedClient\":true}");
    }

    @Test
    void actualSessionCsrfTokenWorksAndPreLoginTokenIsDiscarded() throws Exception {
        var before = send("GET", "/bff/csrf", null, "");
        var oldToken = JSON.readTree(before.body()).path("csrfToken").asText();
        var pending = begin(cookie(before));
        var loggedIn = callback(pending, "code=valid-code&state=" + pending.state());
        var cookie = cookie(loggedIn);
        var current = JSON.readTree(send("GET", "/bff/session", cookie, "").body());
        assertThat(
                        send(
                                        "POST",
                                        "/bff/test-mutate",
                                        cookie,
                                        "",
                                        "Origin",
                                        SPA,
                                        "X-CSRF-TOKEN",
                                        oldToken)
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        "POST",
                                        "/bff/test-mutate",
                                        cookie,
                                        "",
                                        "Origin",
                                        SPA,
                                        current.path("csrfHeaderName").asText(),
                                        current.path("csrfToken").asText())
                                .statusCode())
                .isEqualTo(200);
    }
}
