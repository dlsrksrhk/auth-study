package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sweet.referenceapp.user.application.*;
import com.sweet.referenceapp.user.domain.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.*;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class OAuthSessionRefreshCoordinatorTest {
    final Instant now = Instant.parse("2026-09-10T00:00:00Z");
    final UUID id = UUID.randomUUID();
    final AppUserView view =
            new AppUserView(
                    id,
                    "https://idp.example",
                    "subject",
                    new ExternalUserSnapshot(null, null, null, null, Set.of()),
                    AppUserStatus.ACTIVE,
                    Set.of(AppRole.APP_USER),
                    now,
                    now,
                    now,
                    0);
    final AppOidcUser principal =
            new AppOidcUser(
                    new DefaultOidcUser(
                            List.of(),
                            new OidcIdToken(
                                    "id",
                                    now,
                                    now.plusSeconds(600),
                                    Map.of("iss", "https://idp.example", "sub", "subject"))),
                    view);
    final OAuth2AuthenticationToken auth =
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "reference-app");
    final ClientRegistration registration =
            ClientRegistration.withRegistrationId("reference-app")
                    .clientId("client")
                    .clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://rp.example/callback")
                    .authorizationUri("https://idp.example/auth")
                    .tokenUri("https://idp.example/token")
                    .build();
    final OAuthSessionTokenService tokens = mock(OAuthSessionTokenService.class);
    final AppExternalSnapshotService snapshots = mock(AppExternalSnapshotService.class);
    final OAuthTokenRevoker revoker = mock(OAuthTokenRevoker.class);
    final OAuth2AuthorizedClientRepository repository =
            new HttpSessionOAuth2AuthorizedClientRepository();
    final ExecutorService workers = Executors.newFixedThreadPool(4);
    final MockHttpSession session = new MockHttpSession();
    final ExternalIdentityProfile profile =
            new ExternalIdentityProfile(
                    URI.create("https://idp.example"), "subject", null, null, null, null, Set.of());
    final OAuth2AuthorizedClient successor = client(600, "new");

    @BeforeEach
    void setup() {
        when(tokens.refresh(any(), any())).thenReturn(new RefreshCandidate(successor, profile));
        when(snapshots.refresh(id, profile)).thenReturn(view);
    }

    @AfterEach
    void cleanup() {
        workers.shutdownNow();
    }

    OAuthSessionRefreshCoordinator coordinator(Duration timeout) {
        return new OAuthSessionRefreshCoordinator(
                repository,
                tokens,
                snapshots,
                revoker,
                workers,
                Clock.fixed(now, ZoneOffset.UTC),
                timeout);
    }

    @Test
    void eightRequestsShareOneExchangeAndPublishedResult() throws Exception {
        save(session, client(30, "old"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tokens.refresh(any(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            return new RefreshCandidate(successor, profile);
                        });
        var c = coordinator(Duration.ofSeconds(5));
        var callers = Executors.newFixedThreadPool(8);
        try {
            var start = new CyclicBarrier(8);
            var threads = new CopyOnWriteArrayList<Thread>();
            var results = new ArrayList<Future<Optional<AppUserView>>>();
            for (int i = 0; i < 8; i++)
                results.add(
                        callers.submit(
                                () -> {
                                    threads.add(Thread.currentThread());
                                    start.await();
                                    return c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth);
                                }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long waiting = 0;
            for (int attempt = 0; attempt < 200; attempt++) {
                waiting =
                        threads.stream()
                                .filter(t -> t.getState() == Thread.State.TIMED_WAITING)
                                .count();
                if (waiting == 8) break;
                Thread.sleep(5);
            }
            assertThat(waiting).isEqualTo(8);
            release.countDown();
            for (var result : results) assertThat(result.get(3, TimeUnit.SECONDS)).contains(view);
            assertThat(
                            repository.<OAuth2AuthorizedClient>loadAuthorizedClient(
                                    "reference-app", auth, request(session)))
                    .isSameAs(successor);
            verify(tokens, times(1)).refresh(any(), any());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void thirtyOneSecondsDoesNotRefreshButThirtyDoes() {
        var c = coordinator(Duration.ofSeconds(2));
        save(session, client(31, "old"));
        assertThat(c.ensureFresh(request(session), new MockHttpServletResponse(), auth)).isEmpty();
        verifyNoInteractions(tokens);
        save(session, client(30, "old"));
        assertThat(c.ensureFresh(request(session), new MockHttpServletResponse(), auth))
                .contains(view);
    }

    @Test
    void missingClientPermanentlyClosesState() {
        var c = coordinator(Duration.ofSeconds(2));
        assertThatThrownBy(
                        () -> c.ensureFresh(request(session), new MockHttpServletResponse(), auth))
                .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
        save(session, client(30, "old"));
        assertThatThrownBy(
                        () -> c.ensureFresh(request(session), new MockHttpServletResponse(), auth))
                .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
        verifyNoInteractions(tokens);
    }

    @Test
    void timeoutRevokesLateResultWithoutSavingOrRetry() throws Exception {
        save(session, client(30, "old"));
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(tokens.refresh(any(), any()))
                .thenAnswer(
                        call -> {
                            release.await();
                            return new RefreshCandidate(successor, profile);
                        });
        doAnswer(
                        call -> {
                            revoked.countDown();
                            return null;
                        })
                .when(revoker)
                .revoke(registration, successor.getRefreshToken());
        var c = coordinator(Duration.ofMillis(100));
        try {
            assertThatThrownBy(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth))
                    .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            assertThatThrownBy(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth))
                    .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
        } finally {
            release.countDown();
        }
        assertThat(revoked.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        repository
                                .<OAuth2AuthorizedClient>loadAuthorizedClient(
                                        "reference-app", auth, request(session))
                                .getRefreshToken()
                                .getTokenValue())
                .isEqualTo("old");
        verify(tokens, times(1)).refresh(any(), any());
    }

    @Test
    void invalidationWinsOverLateSnapshot() throws Exception {
        save(session, client(30, "old"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(snapshots.refresh(id, profile))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            return view;
                        });
        doAnswer(
                        call -> {
                            revoked.countDown();
                            return null;
                        })
                .when(revoker)
                .revoke(registration, successor.getRefreshToken());
        var callers = Executors.newSingleThreadExecutor();
        var req = request(session);
        var c = coordinator(Duration.ofSeconds(2));
        try {
            var result =
                    callers.submit(() -> c.ensureFresh(req, new MockHttpServletResponse(), auth));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            new RpSessionCleaner(false).clear(request(session), new MockHttpServletResponse());
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(
                            OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            release.countDown();
            assertThat(revoked.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(req.getSession(false)).isNull();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void subsequentFreshRequestDoesNotReportAnotherRefresh() {
        save(session, client(30, "old"));
        var c = coordinator(Duration.ofSeconds(2));
        assertThat(c.ensureFresh(request(session), new MockHttpServletResponse(), auth))
                .contains(view);
        assertThat(c.ensureFresh(request(session), new MockHttpServletResponse(), auth)).isEmpty();
    }

    @Test
    void protocolAndSnapshotFailuresNeverRetryOldToken() {
        for (boolean database : List.of(false, true)) {
            reset(tokens, snapshots);
            var s = new MockHttpSession();
            save(s, client(30, "old"));
            if (database) {
                when(tokens.refresh(any(), any()))
                        .thenReturn(new RefreshCandidate(successor, profile));
                when(snapshots.refresh(id, profile))
                        .thenThrow(new IllegalStateException("private-db"));
            } else
                when(tokens.refresh(any(), any()))
                        .thenThrow(new IllegalStateException("secret-token"));
            var c = coordinator(Duration.ofSeconds(2));
            for (int i = 0; i < 2; i++)
                assertThatThrownBy(
                                () ->
                                        c.ensureFresh(
                                                request(s), new MockHttpServletResponse(), auth))
                        .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class)
                        .hasMessage("Session refresh failed")
                        .hasNoCause();
            verify(tokens, times(1)).refresh(any(), any());
        }
    }

    @Test
    void anotherSessionProgressesWhileFirstIsBlocked() throws Exception {
        var second = new MockHttpSession();
        save(session, client(30, "first"));
        save(second, client(30, "second"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tokens.refresh(any(), any()))
                .thenAnswer(
                        call -> {
                            OAuth2AuthorizedClient source = call.getArgument(0);
                            if (source.getRefreshToken().getTokenValue().equals("first")) {
                                entered.countDown();
                                release.await();
                            }
                            return new RefreshCandidate(successor, profile);
                        });
        var callers = Executors.newSingleThreadExecutor();
        var c = coordinator(Duration.ofSeconds(3));
        try {
            var first =
                    callers.submit(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(c.ensureFresh(request(second), new MockHttpServletResponse(), auth))
                    .contains(view);
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).contains(view);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void closeWithoutInvalidationWakesOwnerAndRejectsLatePublication() throws Exception {
        save(session, client(30, "old"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(tokens.refresh(any(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            return new RefreshCandidate(successor, profile);
                        });
        doAnswer(
                        call -> {
                            revoked.countDown();
                            return null;
                        })
                .when(revoker)
                .revoke(registration, successor.getRefreshToken());
        var callers = Executors.newSingleThreadExecutor();
        var c = coordinator(Duration.ofSeconds(3));
        try {
            var result =
                    callers.submit(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            OAuthSessionRefreshCoordinator.close(session);
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(
                            OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            release.countDown();
            assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(
                            repository
                                    .<OAuth2AuthorizedClient>loadAuthorizedClient(
                                            "reference-app", auth, request(session))
                                    .getRefreshToken()
                                    .getTokenValue())
                    .isEqualTo("old");
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void repositoryPublicationRunsOnOriginalServletThread() {
        var observing = spy(repository);
        var owner = Thread.currentThread();
        doAnswer(
                        call -> {
                            assertThat(Thread.currentThread()).isSameAs(owner);
                            return call.callRealMethod();
                        })
                .when(observing)
                .saveAuthorizedClient(any(), any(), any(), any());
        var c =
                new OAuthSessionRefreshCoordinator(
                        observing,
                        tokens,
                        snapshots,
                        revoker,
                        workers,
                        Clock.fixed(now, ZoneOffset.UTC),
                        Duration.ofSeconds(2));
        save(session, client(30, "old"));
        assertThat(c.ensureFresh(request(session), new MockHttpServletResponse(), auth))
                .contains(view);
        verify(observing).saveAuthorizedClient(eq(successor), eq(auth), any(), any());
    }

    @Test
    void replacementSessionNeverReceivesOldLoginCandidate() throws Exception {
        save(session, client(30, "old"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(snapshots.refresh(id, profile))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            return view;
                        });
        doAnswer(
                        call -> {
                            revoked.countDown();
                            return null;
                        })
                .when(revoker)
                .revoke(registration, successor.getRefreshToken());
        var callers = Executors.newSingleThreadExecutor();
        var c = coordinator(Duration.ofSeconds(3));
        var req = request(session);
        var replacement = new MockHttpSession();
        try {
            var result =
                    callers.submit(() -> c.ensureFresh(req, new MockHttpServletResponse(), auth));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            req.setSession(replacement);
            release.countDown();
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(
                            OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(
                            repository.<OAuth2AuthorizedClient>loadAuthorizedClient(
                                    "reference-app", auth, request(replacement)))
                    .isNull();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void databaseDeadlineDiscardsCandidateEvenThoughSnapshotEventuallySucceeds() throws Exception {
        save(session, client(30, "old"));
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(snapshots.refresh(id, profile))
                .thenAnswer(
                        call -> {
                            release.await();
                            return view;
                        });
        doAnswer(
                        call -> {
                            revoked.countDown();
                            return null;
                        })
                .when(revoker)
                .revoke(registration, successor.getRefreshToken());
        var c = coordinator(Duration.ofMillis(100));
        try {
            assertThatThrownBy(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth))
                    .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
        } finally {
            release.countDown();
        }
        assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        repository
                                .<OAuth2AuthorizedClient>loadAuthorizedClient(
                                        "reference-app", auth, request(session))
                                .getRefreshToken()
                                .getTokenValue())
                .isEqualTo("old");
    }

    @Test
    void eightRequestsShareFailureAndCannotReuseOldToken() throws Exception {
        save(session, client(30, "old"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tokens.refresh(any(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            throw new IllegalStateException("secret-token");
                        });
        var callers = Executors.newFixedThreadPool(8);
        var c = coordinator(Duration.ofSeconds(3));
        try {
            var start = new CyclicBarrier(8);
            var results = new ArrayList<Future<Optional<AppUserView>>>();
            for (int i = 0; i < 8; i++)
                results.add(
                        callers.submit(
                                () -> {
                                    start.await();
                                    return c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth);
                                }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var result : results)
                assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(
                                OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            assertThatThrownBy(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth))
                    .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class);
            verify(tokens, times(1)).refresh(any(), any());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void absentExpiryTerminatesWithoutExchangeOrRetry() {
        var malformed =
                new OAuth2AuthorizedClient(
                        registration,
                        "subject",
                        new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER, "access", now, null),
                        new OAuth2RefreshToken("old", now));
        save(session, malformed);
        var c = coordinator(Duration.ofSeconds(2));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(
                            () ->
                                    c.ensureFresh(
                                            request(session), new MockHttpServletResponse(), auth))
                    .isInstanceOf(OAuthSessionRefreshCoordinator.SessionRefreshException.class)
                    .hasMessage("Session refresh failed")
                    .hasNoCause();
        }
        verifyNoInteractions(tokens, snapshots);
    }

    OAuth2AuthorizedClient client(long seconds, String refresh) {
        return new OAuth2AuthorizedClient(
                registration,
                "subject",
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access",
                        now.minusSeconds(60),
                        now.plusSeconds(seconds)),
                new OAuth2RefreshToken(refresh, now.minusSeconds(60)));
    }

    void save(MockHttpSession s, OAuth2AuthorizedClient client) {
        repository.saveAuthorizedClient(client, auth, request(s), new MockHttpServletResponse());
    }

    static MockHttpServletRequest request(MockHttpSession s) {
        var r = new MockHttpServletRequest();
        r.setSession(s);
        return r;
    }
}
