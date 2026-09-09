package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class OidcLocalLoginIntegrationTest extends LocalLoginHttpTestSupport {
    @Test
    void normalLoginRotatesSessionCreatesUserAndReturnsPublicContracts() throws Exception {
        var anonymous = send("GET", "/bff/session", null, "");
        assertThat(anonymous.body()).isEqualTo("{\"authenticated\":false}");
        assertThat(anonymous.headers().allValues("Set-Cookie")).isEmpty();
        var pending = begin(null);
        var result = callback(pending, "code=valid-code&state=" + pending.state());
        assertThat(result.headers().firstValue("Location")).contains(SPA + "/");
        var cookie = cookie(result);
        assertThat(cookie).isNotEqualTo(pending.cookie());
        assertThat(send("GET", "/bff/profile", pending.cookie(), "").statusCode()).isEqualTo(401);
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
        var session = send("GET", "/bff/session", cookie, "");
        var tree = JSON.readTree(session.body());
        assertThat(tree.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("authenticated", "user", "csrfHeaderName", "csrfToken");
        assertThat(tree.path("authenticated").asBoolean()).isTrue();
        assertThat(tree.path("user").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "displayName", "email", "status", "roles");
        assertThat(tree.path("user").path("status").asText()).isEqualTo("ACTIVE");
        var profile = send("GET", "/bff/profile", cookie, "");
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(profile.body()))
                .isEqualTo(
                        JSON.readTree(
                                """
                                {"displayName":"Reference User","email":"user@example.test","company":null,"organization":null,"hrRoles":[],"roles":["APP_USER"]}
                                """));
        for (var response : List.of(result, session, profile, anonymous)) {
            assertNoTokenLeak(response);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .contains("no-store");
        }
    }

    @Test
    void reloginPreservesUuidAndLocalRolesButUpdatesSnapshot() throws Exception {
        var first = login();
        var id = userId(first);
        jdbc.update("insert into app_user_role values (?, 'APP_ADMIN')", id);
        ISSUER.userInfoClaims =
                Map.of(
                        "sub",
                        ISSUER.subject,
                        "name",
                        "Updated User",
                        "email",
                        "updated@example.test");
        var second = login();
        assertThat(userId(second)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
        var profile = JSON.readTree(send("GET", "/bff/profile", second, "").body());
        assertThat(profile.path("displayName").asText()).isEqualTo("Updated User");
        assertThat(profile.path("email").asText()).isEqualTo("updated@example.test");
        assertThat(profile.path("roles").toString()).isEqualTo("[\"APP_ADMIN\",\"APP_USER\"]");
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(2);
    }

    @Test
    void onlyFirstEligibleUserBootstrapsAdministrator() throws Exception {
        ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, HR_ROLES, List.of("COMPANY_ADMIN"));
        var first = login();
        var id = userId(first);
        assertThat(send("GET", "/bff/test-admin", first, "").statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select bootstrapped_user_id from app_bootstrap_state", UUID.class))
                .isEqualTo(id);
        ISSUER.subject = "second-user";
        ISSUER.userInfoClaims =
                Map.of("sub", ISSUER.subject, HR_ROLES, List.of("COMPANY_ADMIN", "APP_ADMIN"));
        var second = login();
        assertThat(userId(second)).isNotEqualTo(id);
        assertThat(send("GET", "/bff/test-admin", second, "").statusCode()).isEqualTo(403);
        assertThat(
                        JSON.readTree(send("GET", "/bff/profile", second, "").body())
                                .path("roles")
                                .toString())
                .isEqualTo("[\"APP_USER\"]");
        assertThat(
                        jdbc.queryForObject(
                                "select bootstrapped_user_id from app_bootstrap_state", UUID.class))
                .isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "numeric-name",
                "numeric-email",
                "numeric-sub",
                "missing-sub",
                "mismatched-sub",
                "userinfo-error"
            })
    void invalidUserInfoLeavesDatabaseUnchanged(String fault) throws Exception {
        var before = databaseState();
        var pending = begin(null);
        ISSUER.fault = fault;
        assertFailure(
                callback(pending, "code=valid-code&state=" + pending.state()),
                pending.cookie(),
                "oidc_login_failed");
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(1);
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void invalidNestedClaimAndRemoteDisabledCodeUseGenericFailure() throws Exception {
        ISSUER.userInfoClaims =
                Map.of(
                        "sub",
                        ISSUER.subject,
                        "https://auth-study.local/claims/company",
                        Map.of("code", "ACME", "name", "Acme", "private", "forbidden"));
        var pending = begin(null);
        assertFailure(
                callback(pending, "code=valid-code&state=" + pending.state()),
                pending.cookie(),
                "oidc_login_failed");
        assertEmptyDatabase();
        var remote = begin(null);
        assertFailure(
                callback(remote, "error=local_user_disabled&state=" + remote.state()),
                remote.cookie(),
                "oidc_login_failed");
        assertEmptyDatabase();
    }

    @Test
    void disabledLoginRollsBackSnapshotTimesRolesAndBootstrapAndDiscardsOldSession()
            throws Exception {
        var old = login();
        var id = userId(old);
        jdbc.update("update app_user set status='DISABLED' where id=?", id);
        var before = databaseState();
        ISSUER.userInfoClaims =
                Map.of(
                        "sub",
                        ISSUER.subject,
                        "name",
                        "Must Roll Back",
                        HR_ROLES,
                        List.of("COMPANY_ADMIN"));
        var pending = begin(old);
        assertFailure(
                callback(pending, "code=valid-code&state=" + pending.state()),
                old,
                "local_user_disabled");
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void committedInsertFailureRollsBackJitAndBootstrapAndLeavesNoSession() throws Exception {
        ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, HR_ROLES, List.of("COMPANY_ADMIN"));
        jdbc.execute(
                "CREATE FUNCTION reject_test_user() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " RAISE EXCEPTION 'test storage rejection'; END $$");
        try {
            jdbc.execute(
                    "CREATE TRIGGER reject_test_user BEFORE INSERT ON app_user FOR EACH ROW EXECUTE"
                        + " FUNCTION reject_test_user()");
            var pending = begin(null);
            assertFailure(
                    callback(pending, "code=valid-code&state=" + pending.state()),
                    pending.cookie(),
                    "oidc_login_failed");
            assertEmptyDatabase();
            assertThat(jdbc.queryForObject("select version from app_bootstrap_state", Long.class))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_test_user ON app_user");
            jdbc.execute("DROP FUNCTION reject_test_user()");
        }
    }
}
