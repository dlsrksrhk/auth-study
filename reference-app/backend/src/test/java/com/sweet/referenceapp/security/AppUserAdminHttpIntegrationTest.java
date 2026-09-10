package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppUserAdminHttpIntegrationTest extends LocalLoginHttpTestSupport {
    @Autowired DataSource dataSource;
    private static final String USERS = "/bff/admin/users";

    // Catches security matcher removal and accidental use of external HR roles for local authorization.
    @Test void onlyLocalAdminsCanReadAndEverySecurityResponseIsNotCached() throws Exception {
        checked(send("GET", USERS, null, ""), 401);
        String admin = loginAs("admin", true);
        String ordinary = loginAs("ordinary", false);
        String hrOnly = loginAs("hr-only", true);
        checked(send("GET", USERS, ordinary, ""), 403);
        checked(send("GET", USERS, hrOnly, ""), 403);
        checked(send("GET", USERS, admin, ""), 200);
    }

    // Catches DTO expansion leaking identity/authentication data and loss of optimistic/no-op semantics.
    @Test void fourEndpointsExposeOnlyTheContractAndPreserveNoOpAndConflictSemantics() throws Exception {
        String admin = loginAs("admin", true);
        UUID id = userId(admin);
        JsonNode page = checked(send("GET", USERS + "?status=ACTIVE&role=APP_ADMIN", admin, ""), 200);
        assertKeys(page, "items", "page", "size", "totalElements", "totalPages");
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertKeys(page.path("items").get(0), "id", "displayName", "email", "status", "roles", "lastLoginAt", "version");
        JsonNode original = detail(admin, id);
        assertKeys(original, "id", "displayName", "email", "status", "roles", "lastLoginAt", "version", "createdAt", "updatedAt", "externalIdentity", "company", "organization", "hrRoles");
        assertKeys(original.path("externalIdentity"), "issuer", "subject");
        assertThat(original.path("externalIdentity").path("subject").asText()).isEqualTo("admin");
        assertThat(original.path("company").isNull()).isTrue();
        assertThat(original.path("organization").isNull()).isTrue();
        long version = original.path("version").asLong();
        assertThat(checked(put(admin, id, "status", "\"ACTIVE\"", version), 200)).isEqualTo(original);
        problem(put(admin, id, "status", "\"DISABLED\"", version), 409, "LAST_ACTIVE_ADMIN_REQUIRED");
        problem(put(admin, id, "roles", "[\"APP_USER\"]", version), 409, "LAST_ACTIVE_ADMIN_REQUIRED");
        problem(put(admin, id, "status", "\"ACTIVE\"", version + 1), 409, "OPTIMISTIC_LOCK_CONFLICT");
        problem(send("GET", USERS + "?size=101", admin, ""), 400, "INVALID_REQUEST");
        problem(send("GET", USERS + "/" + UUID.randomUUID(), admin, ""), 404, "APP_USER_NOT_FOUND");
        assertThat(detail(admin, id)).isEqualTo(original);
    }

    // Catches cached session authorities and failure to re-read disabled users on subsequent requests.
    @Test void roleGrantRevocationAndDisableApplyToTheTargetsNextRequest() throws Exception {
        String admin = loginAs("admin", true);
        String target = loginAs("target", false);
        UUID id = userId(target);
        JsonNode before = detail(admin, id);
        JsonNode granted = checked(put(admin, id, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", before.path("version").asLong()), 200);
        assertThat(granted.path("version").asLong()).isGreaterThan(before.path("version").asLong());
        for (String field : List.of("createdAt", "lastLoginAt", "externalIdentity", "displayName", "email", "company", "organization", "hrRoles")) {
            assertThat(granted.path(field)).as(field).isEqualTo(before.path(field));
        }
        checked(send("GET", USERS, target, ""), 200);
        checked(put(admin, id, "roles", "[\"APP_USER\"]", detail(admin, id).path("version").asLong()), 200);
        checked(send("GET", USERS, target, ""), 403);
        checked(put(admin, id, "status", "\"DISABLED\"", detail(admin, id).path("version").asLong()), 200);
        assertThat(send("GET", "/bff/profile", target, "").statusCode()).isEqualTo(401);
        var session = send("GET", "/bff/session", target, "");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(session.body())).isEqualTo(JSON.readTree("{\"authenticated\":false}"));
    }

    @Test void selfDemotionReturnsSuccessBeforeTheNextRequestIsForbidden() throws Exception {
        String admin = loginAs("admin", true);
        String second = loginAs("second", false);
        UUID secondId = userId(second);
        checked(put(admin, secondId, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", detail(admin, secondId).path("version").asLong()), 200);
        UUID id = userId(admin);
        checked(put(admin, id, "roles", "[\"APP_USER\"]", detail(admin, id).path("version").asLong()), 200);
        checked(send("GET", USERS, admin, ""), 403);
        checked(send("GET", USERS, second, ""), 200);
    }

    @Test void selfDisableReturnsSuccessBeforeTheNextRequestEndsTheSession() throws Exception {
        String admin = loginAs("admin", true);
        String second = loginAs("second", false);
        UUID secondId = userId(second);
        checked(put(admin, secondId, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", detail(admin, secondId).path("version").asLong()), 200);
        UUID id = userId(admin);
        checked(put(admin, id, "status", "\"DISABLED\"", detail(admin, id).path("version").asLong()), 200);
        checked(send("GET", USERS, admin, ""), 401);
        assertThat(send("GET", "/bff/profile", admin, "").statusCode()).isEqualTo(401);
        var session = send("GET", "/bff/session", admin, "");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(session.body()).path("authenticated").asBoolean()).isFalse();
        checked(send("GET", USERS, second, ""), 200);
    }

    @Test void csrfAndOriginFailuresDoNotChangeTheDatabase() throws Exception {
        String admin = loginAs("admin", true);
        String target = loginAs("target", false);
        UUID id = userId(target);
        String body = "{\"status\":\"DISABLED\",\"version\":" + detail(admin, id).path("version").asLong() + "}";
        var before = databaseState();
        checked(send("PUT", USERS + "/" + id + "/status", admin, body, "Content-Type", "application/json", "Origin", SPA), 403);
        checked(send("PUT", USERS + "/" + id + "/status", admin, body, "Content-Type", "application/json", "Origin", "https://untrusted.example", "X-CSRF-TOKEN", csrfToken(admin)), 403);
        assertThat(databaseState()).isEqualTo(before);
    }

    // A real separate connection holds the singleton until the HTTP service's database timeout fires.
    @Test void singletonLockTimeoutReturnsSafe503AndRollsBackTheEntireMutation() throws Exception {
        String admin = loginAs("admin", true);
        String target = loginAs("target", false);
        UUID id = userId(target);
        long version = detail(admin, id).path("version").asLong();
        var before = databaseState();
        try (var gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try {
                try (var statement = gate.createStatement(); var rows = statement.executeQuery("select singleton_key from app_bootstrap_state where singleton_key=1 for update")) {
                    assertThat(rows.next()).isTrue();
                }
                problem(put(admin, id, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", version), 503, "SERVICE_UNAVAILABLE");
                assertThat(databaseState()).isEqualTo(before);
            } finally {
                gate.rollback();
            }
        }
        checked(put(admin, id, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", version), 200);
    }

    @Test void snapshotRefreshMakesAnEarlierVersionStaleWithoutOverwritingRolesOrSnapshot() throws Exception {
        String admin = loginAs("admin", true);
        ISSUER.initialAccessTokenLifetime = 20;
        String target = loginAs("target", false);
        // Do not request target's session yet: that would refresh its short-lived access token.
        UUID id = jdbc.queryForObject("select id from app_user where subject = ?", UUID.class, "target");
        long originalVersion = detail(admin, id).path("version").asLong();
        JsonNode promoted = checked(put(admin, id, "roles", "[\"APP_USER\",\"APP_ADMIN\"]", originalVersion), 200);
        ISSUER.userInfoClaims = Map.of("sub", "target", "name", "Refreshed Name", "email", "new@example.test", HR_ROLES, List.of("COMPANY_ADMIN"));
        assertThat(send("GET", "/bff/profile", target, "").statusCode()).isEqualTo(200);
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(1);
        JsonNode refreshed = detail(admin, id);
        assertThat(refreshed.path("version").asLong()).isGreaterThan(promoted.path("version").asLong());
        assertThat(refreshed.path("displayName").asText()).isEqualTo("Refreshed Name");
        assertThat(refreshed.path("roles")).isEqualTo(JSON.readTree("[\"APP_USER\",\"APP_ADMIN\"]"));
        problem(put(admin, id, "roles", "[\"APP_USER\"]", promoted.path("version").asLong()), 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(detail(admin, id)).isEqualTo(refreshed);
    }

    private JsonNode detail(String cookie, UUID id) throws Exception {
        return checked(send("GET", USERS + "/" + id, cookie, ""), 200);
    }

    private static HttpResponse<String> put(String cookie, UUID id, String field, String value, long version) throws Exception {
        return send("PUT", USERS + "/" + id + "/" + field, cookie,
                "{\"" + field + "\":" + value + ",\"version\":" + version + "}",
                "Content-Type", "application/json", "Origin", SPA, "X-CSRF-TOKEN", csrfToken(cookie));
    }

    private static JsonNode checked(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertNoTokenLeak(response);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) throws Exception {
        JsonNode body = checked(response, status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertKeys(body, "type", "title", "status", "detail", "instance", "code");
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("instance").asText()).isEqualTo(response.request().uri().getPath());
        assertThat(response.body()).doesNotContain("select ", "org.hibernate", "SQLException", "test-reference-secret");
    }

    private static void assertKeys(JsonNode node, String... expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }
}
