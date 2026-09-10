package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenLifecycleHttpIntegrationTest extends LocalLoginHttpTestSupport {
    @org.springframework.beans.factory.annotation.Autowired OAuthTokenLifecycleProperties lifecycle;
    @Test void concurrentProfilesShareOneRefreshAndCommittedSnapshot() throws Exception {
        String cookie = expiringLogin();
        var before = jdbc.queryForMap("select * from app_user");
        var roles = jdbc.queryForList("select * from app_user_role");
        int infos = ISSUER.userInfoRequestCount();
        ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, "name", "Refreshed Name", "email", "new@example.test");
        blockRefresh();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var responses = new ArrayList<Future<HttpResponse<String>>>();
            var start = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) responses.add(pool.submit(() -> { start.await(); return send("GET", "/bff/profile", cookie, ""); }));
            start.countDown();
            await(ISSUER.refreshEntered);
            ISSUER.refreshRelease.countDown();
            for (var future : responses) {
                var response = future.get(5, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(response.body()).path("displayName").asText()).isEqualTo("Refreshed Name");
                assertNoTokenLeak(response);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            }
        } finally { ISSUER.refreshRelease.countDown(); }
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(1);
        assertThat(ISSUER.userInfoRequestCount() - infos).isEqualTo(1);
        var after = jdbc.queryForMap("select * from app_user");
        for (String column : new String[]{"id", "issuer", "subject", "status", "created_at", "last_login_at"})
            assertThat(after.get(column)).isEqualTo(before.get(column));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertThat(jdbc.queryForList("select * from app_user_role")).isEqualTo(roles);
        assertThat(ISSUER.revocationRequestCount()).isZero();
    }

    @Test void logoutDuringTokenExchangeRejectsAndRevokesSuccessorBeforeDeadline() throws Exception {
        String cookie = expiringLogin();
        blockRefresh();
        ISSUER.requestLatch = new CountDownLatch(2);
        try (var pool = Executors.newSingleThreadExecutor()) {
            // This starts before the coordinator can create its deadline, so it is a conservative bound.
            long started = System.nanoTime();
            var profile = pool.submit(() -> send("GET", "/bff/profile", cookie, ""));
            await(ISSUER.refreshEntered);
            var out = logout("/bff/logout", cookie);
            // Let the successor arrive immediately after logout, without waiting for the owner request.
            ISSUER.refreshRelease.countDown();
            assertThat(out.statusCode()).isEqualTo(204);
            assertDeletion(out);
            assertNoTokenLeak(out);
            assertThat(profile.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            await(ISSUER.requestLatch);
            assertRevokedPair();
            // Both the owner failure and successor receipt/revocation must precede even this earlier bound.
            assertThat(System.nanoTime() - started).isLessThan(lifecycle.refreshTimeout().toNanos());
            assertTerminated(cookie);
        } finally { ISSUER.refreshRelease.countDown(); }
    }

    @Test void logoutAfterDatabaseCommitBeforePublicationLeavesSnapshotButNoSession() throws Exception {
        String cookie = expiringLogin();
        ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, "name", "Committed Before Logout");
        snapshotCommitted = new CountDownLatch(1);
        snapshotRelease = new CountDownLatch(1);
        ISSUER.requestLatch = new CountDownLatch(2);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var profile = pool.submit(() -> send("GET", "/bff/profile", cookie, ""));
            await(snapshotCommitted);
            // Independent connection reads the actual committed value while publication is gated.
            assertThat(jdbc.queryForObject("select display_name from app_user", String.class)).isEqualTo("Committed Before Logout");
            var out = logout("/bff/logout", cookie);
            assertThat(out.statusCode()).isEqualTo(204);
            assertDeletion(out);
            assertThat(profile.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            snapshotRelease.countDown();
            await(ISSUER.requestLatch);
            assertRevokedPair();
            assertTerminated(cookie);
            assertThat(jdbc.queryForObject("select display_name from app_user", String.class)).isEqualTo("Committed Before Logout");
        } finally { snapshotRelease.countDown(); }
    }

    @Test void tokenArrivingAfterWorkDeadlineIsRevokedWithoutRestoringSession() throws Exception {
        String cookie = expiringLogin();
        blockRefresh();
        ISSUER.requestLatch = new CountDownLatch(2);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var profile = pool.submit(() -> send("GET", "/bff/profile", cookie, ""));
            await(ISSUER.refreshEntered);
            // Gate remains closed until the HTTP request hits its configured 1500ms work bound.
            var failed = profile.get(4, TimeUnit.SECONDS);
            assertThat(failed.statusCode()).isEqualTo(401);
            assertDeletion(failed);
            assertNoTokenLeak(failed);
            assertTerminated(cookie);
            ISSUER.refreshRelease.countDown();
            await(ISSUER.requestLatch);
            assertRevokedPair();
            assertTerminated(cookie);
        } finally { ISSUER.refreshRelease.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"token-error", "userinfo-error", "numeric-sub", "missing-sub", "mismatched-sub"})
    void protocolFailureTerminatesSessionWithoutBusinessEntryOrRetry(String fault) throws Exception {
        String cookie = expiringLogin();
        String csrf = csrfToken(cookie);
        int infos = ISSUER.userInfoRequestCount();
        ISSUER.fault = fault;
        ISSUER.requestLatch = new CountDownLatch(fault.equals("token-error") ? 1 : 2);
        var response = send("POST", "/bff/test-mutate", cookie, "", "Origin", SPA, "X-CSRF-TOKEN", csrf);
        assertThat(response.statusCode()).isEqualTo(401);
        assertDeletion(response);
        assertNoTokenLeak(response);
        assertThat(businessCalls.get()).isZero();
        await(ISSUER.requestLatch);
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(1);
        assertTerminated(cookie);
        assertThat(ISSUER.userInfoRequestCount() - infos).isEqualTo(fault.equals("token-error") ? 0 : 1);
        if (!fault.equals("token-error")) assertRevokedPair();
    }

    @Test void sessionEndpointRefreshFailureReturnsAnonymous200() throws Exception {
        String cookie = expiringLogin();
        ISSUER.fault = "token-error";
        var response = send("GET", "/bff/session", cookie, "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"authenticated\":false}");
        assertDeletion(response);
        assertNoTokenLeak(response);
        assertTerminated(cookie);
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(1);
    }

    @Test void databaseWriteFailureRollsBackAndRevokesCandidate() throws Exception {
        String cookie = expiringLogin();
        var before = databaseState();
        jdbc.execute("alter table app_user add constraint lifecycle_test_reject_name check (display_name <> 'Rejected Refresh')");
        try {
            ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, "name", "Rejected Refresh");
            ISSUER.requestLatch = new CountDownLatch(2);
            var response = send("POST", "/bff/test-mutate", cookie, "", "Origin", SPA, "X-CSRF-TOKEN", csrfToken(cookie));
            assertThat(response.statusCode()).isEqualTo(401);
            assertDeletion(response);
            assertNoTokenLeak(response);
            assertThat(businessCalls.get()).isZero();
            await(ISSUER.requestLatch);
            assertRevokedPair();
            assertTerminated(cookie);
            assertThat(databaseState()).isEqualTo(before);
        } finally { jdbc.execute("alter table app_user drop constraint lifecycle_test_reject_name"); }
    }

    @Test void excludedRoutesAndCsrfOriginDenialsNeverRefreshExpiringToken() throws Exception {
        String cookie = expiringLogin();
        int tokens = ISSUER.tokenRequestCount();
        int infos = ISSUER.userInfoRequestCount();
        assertThat(send("GET", "/bff/csrf", cookie, "").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/bff/login", cookie, "").statusCode()).isEqualTo(302);
        assertThat(send("GET", "/bff/logout/continue/not-a-ticket", cookie, "").statusCode()).isEqualTo(410);
        assertThat(send("POST", "/bff/test-mutate", cookie, "", "Origin", SPA).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/bff/test-mutate", cookie, "", "Origin", "https://evil.example", "X-CSRF-TOKEN", csrfToken(cookie)).statusCode()).isEqualTo(403);
        assertThat(logout("/bff/logout/identity-provider", cookie).statusCode()).isEqualTo(200);
        assertTerminated(cookie);
        assertThat(ISSUER.tokenRequestCount()).isEqualTo(tokens);
        assertThat(ISSUER.userInfoRequestCount()).isEqualTo(infos);
        assertThat(businessCalls.get()).isZero();
    }

    static String expiringLogin() throws Exception { ISSUER.initialAccessTokenLifetime = 20; return login(); }
    static void blockRefresh() { ISSUER.refreshEntered = new CountDownLatch(1); ISSUER.refreshRelease = new CountDownLatch(1); }
    static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(7, TimeUnit.SECONDS)).isTrue(); }
    static void assertRevokedPair() {
        assertThat(ISSUER.revokedTokens).containsExactlyInAnyOrder("test-refresh-token", "test-refresh-token-refreshed");
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(1);
    }
    static void assertTerminated(String cookie) throws Exception {
        int refreshes = ISSUER.refreshRequestCount();
        var session = send("GET", "/bff/session", cookie, "");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.body()).isEqualTo("{\"authenticated\":false}");
        assertThat(session.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(send("GET", "/bff/profile", cookie, "").statusCode()).isEqualTo(401);
        assertNoTokenLeak(session);
        assertThat(ISSUER.refreshRequestCount()).isEqualTo(refreshes);
    }
}
