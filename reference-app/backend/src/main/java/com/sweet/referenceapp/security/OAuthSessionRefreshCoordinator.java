package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppExternalSnapshotService;
import com.sweet.referenceapp.user.application.AppUserView;

import jakarta.servlet.http.*;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.util.WebUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

public final class OAuthSessionRefreshCoordinator {
    private static final String ATTRIBUTE = OAuthSessionRefreshCoordinator.class.getName();
    private final OAuth2AuthorizedClientRepository clients;
    private final OAuthSessionTokenService tokens;
    private final AppExternalSnapshotService snapshots;
    private final OAuthTokenRevoker revoker;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    public OAuthSessionRefreshCoordinator(
            OAuth2AuthorizedClientRepository clients,
            OAuthSessionTokenService tokens,
            AppExternalSnapshotService snapshots,
            OAuthTokenRevoker revoker,
            Executor executor,
            Clock clock,
            Duration timeout) {
        this.clients = clients;
        this.tokens = tokens;
        this.snapshots = snapshots;
        this.revoker = revoker;
        this.executor = executor;
        this.clock = clock;
        this.timeout = timeout;
    }

    public Optional<AppUserView> ensureFresh(
            HttpServletRequest request,
            HttpServletResponse response,
            OAuth2AuthenticationToken authentication) {
        var session = request.getSession(false);
        if (session == null || !(authentication.getPrincipal() instanceof AppOidcUser principal))
            throw new SessionRefreshException();
        State state;
        Object mutex;
        OAuth2AuthorizedClient current;
        CompletableFuture<AppUserView> shared;
        boolean owner;
        try {
            mutex = WebUtils.getSessionMutex(session);
            synchronized (mutex) {
                state = (State) session.getAttribute(ATTRIBUTE);
                if (state == null) {
                    state = new State(mutex);
                    session.setAttribute(ATTRIBUTE, state);
                }
                if (state.closing) throw new SessionRefreshException();
                owner = state.future == null || state.future.isDone();
                if (owner) {
                    // Always reread under ownership: an entering request may have seen a rotated
                    // token.
                    current =
                            clients.loadAuthorizedClient(
                                    authentication.getAuthorizedClientRegistrationId(),
                                    authentication,
                                    request);
                    if (current == null || current.getAccessToken().getExpiresAt() == null) {
                        state.close();
                        throw new SessionRefreshException();
                    }
                    if (current.getAccessToken().getExpiresAt() != null
                            && current.getAccessToken()
                                    .getExpiresAt()
                                    .isAfter(clock.instant().plusSeconds(30))) {
                        return Optional.empty();
                    }
                    state.deadline = System.nanoTime() + timeout.toNanos();
                    state.future = new CompletableFuture<>();
                } else current = null;
                shared = state.future;
            }
        } catch (IllegalStateException invalid) {
            throw new SessionRefreshException();
        }
        if (!owner) return Optional.of(await(shared, state));

        var work = new Work();
        try {
            var source = current;
            executor.execute(() -> refresh(source, principal, work));
            // Closing the session releases the servlet owner even while network/DB work is blocked.
            await(CompletableFuture.anyOf(work.ready, shared), state);
            var view = work.ready.join();
            synchronized (mutex) {
                if (state.closing
                        || System.nanoTime() >= state.deadline
                        || request.getSession(false) != session) {
                    state.close();
                    throw new SessionRefreshException();
                }
                clients.saveAuthorizedClient(
                        work.candidate.client(), authentication, request, response);
                work.accepted.complete(true);
                shared.complete(view);
            }
            return Optional.of(view);
        } catch (RuntimeException failure) {
            synchronized (mutex) {
                state.close();
            }
            throw new SessionRefreshException();
        } finally {
            work.accepted.complete(false);
        }
    }

    private <T> T await(CompletableFuture<T> future, State state) {
        try {
            return future.get(
                    Math.max(0, state.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            // Do not attach protocol failures or token-bearing causes to the public exception.
        }
        synchronized (state.mutex) {
            state.close();
        }
        throw new SessionRefreshException();
    }

    private void refresh(OAuth2AuthorizedClient source, AppOidcUser principal, Work work) {
        RefreshCandidate candidate = null;
        boolean accepted = false;
        try {
            candidate = tokens.refresh(source, principal);
            var view = snapshots.refresh(principal.localUserId(), candidate.profile());
            work.candidate = candidate;
            work.ready.complete(view);
            accepted = work.accepted.join();
        } catch (RuntimeException failure) {
            work.ready.completeExceptionally(new SessionRefreshException());
        } finally {
            work.candidate = null;
            if (!accepted && candidate != null)
                revoker.revoke(
                        candidate.client().getClientRegistration(),
                        candidate.client().getRefreshToken());
        }
    }

    public static void close(HttpSession session) {
        if (session == null) return;
        try {
            synchronized (WebUtils.getSessionMutex(session)) {
                var state = (State) session.getAttribute(ATTRIBUTE);
                if (state != null) state.close();
            }
        } catch (IllegalStateException invalid) {
            /* Already closed. */
        }
    }

    // This transient handoff belongs solely to the owner and worker, never to the session.
    // The worker retains revocation responsibility until the servlet acknowledges publication.
    private static final class Work {
        volatile RefreshCandidate candidate;
        final CompletableFuture<AppUserView> ready = new CompletableFuture<>();
        final CompletableFuture<Boolean> accepted = new CompletableFuture<>();
    }

    private static final class State implements HttpSessionBindingListener {
        final Object mutex;
        boolean closing;
        CompletableFuture<AppUserView> future;
        long deadline;

        State(Object mutex) {
            this.mutex = mutex;
        }

        void close() {
            closing = true;
            if (future != null) future.completeExceptionally(new SessionRefreshException());
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            synchronized (mutex) {
                close();
            }
        }
    }

    public static final class SessionRefreshException extends RuntimeException {
        public SessionRefreshException() {
            super("Session refresh failed", null, false, false);
        }
    }
}
