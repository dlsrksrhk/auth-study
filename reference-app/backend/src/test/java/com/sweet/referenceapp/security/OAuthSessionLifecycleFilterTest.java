package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sweet.referenceapp.user.application.*;
import com.sweet.referenceapp.user.domain.*;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

class OAuthSessionLifecycleFilterTest {
    final OAuthSessionRefreshCoordinator coordinator = mock(OAuthSessionRefreshCoordinator.class);
    final CurrentAppUserService users = mock(CurrentAppUserService.class);
    final OAuthSessionRefreshCoordinatorTest fixture = new OAuthSessionRefreshCoordinatorTest();
    final OAuthSessionLifecycleFilter filter =
            new OAuthSessionLifecycleFilter(coordinator, users, new RpSessionCleaner(false));

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        fixture.cleanup();
    }

    @Test
    void successRechecksLatestRoleAndUsesRequestLocalContext() throws Exception {
        var req = request("/bff/profile");
        var shared = SecurityContextHolder.getContext();
        var old = fixture.view;
        var latest =
                new AppUserView(
                        old.id(),
                        old.issuer(),
                        old.subject(),
                        old.snapshot(),
                        old.status(),
                        Set.of(AppRole.APP_ADMIN),
                        old.createdAt(),
                        old.updatedAt(),
                        old.lastLoginAt(),
                        old.version());
        when(coordinator.ensureFresh(any(), any(), any())).thenReturn(Optional.of(old));
        when(users.find(old.id())).thenReturn(Optional.of(latest));
        filter.doFilter(
                req,
                new MockHttpServletResponse(),
                (r, s) -> {
                    assertThat(CurrentAppUser.find(req)).contains(latest);
                    assertThat(
                                    SecurityContextHolder.getContext()
                                            .getAuthentication()
                                            .getAuthorities())
                            .extracting("authority")
                            .containsExactly("APP_ADMIN");
                });
        assertThat(shared.getAuthentication()).isSameAs(fixture.auth);
    }

    @Test
    void refreshFailureClearsContextAndOnlySessionContinuesAnonymously() throws Exception {
        for (var path : List.of("/bff/session", "/bff/profile")) {
            var req = request(path);
            CurrentAppUser.set(req, fixture.view);
            var session = (MockHttpSession) req.getSession(false);
            var res = new MockHttpServletResponse();
            var continued = new java.util.concurrent.atomic.AtomicBoolean();
            doThrow(new OAuthSessionRefreshCoordinator.SessionRefreshException())
                    .when(coordinator)
                    .ensureFresh(any(), any(), any());
            filter.doFilter(
                    req,
                    res,
                    (r, s) -> {
                        continued.set(true);
                        assertThat(CurrentAppUser.find(req)).isEmpty();
                        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                    });
            assertThat(continued.get()).isEqualTo(path.equals("/bff/session"));
            assertThat(res.getStatus()).isEqualTo(path.equals("/bff/session") ? 200 : 401);
            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Test
    void missingUserAfterRefreshEndsSession() throws Exception {
        var req = request("/bff/profile");
        when(coordinator.ensureFresh(any(), any(), any())).thenReturn(Optional.of(fixture.view));
        when(users.find(any())).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();
        filter.doFilter(
                req,
                res,
                (r, s) -> {
                    throw new AssertionError("must stop");
                });
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(req.getSession(false)).isNull();
    }

    @Test
    void skipsLoginCsrfLogoutContinuationAndNonRequestDispatch() throws Exception {
        for (var path :
                List.of(
                        "/bff/login",
                        "/bff/csrf",
                        "/bff/logout",
                        "/bff/logout/identity-provider",
                        "/bff/logout/continue/ticket",
                        "/other")) {
            var req = request(path);
            req.setMethod("POST");
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        }
        for (var type :
                List.of(
                        DispatcherType.ASYNC,
                        DispatcherType.ERROR,
                        DispatcherType.FORWARD,
                        DispatcherType.INCLUDE)) {
            var req = request("/bff/profile");
            req.setDispatcherType(type);
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        }
        verifyNoInteractions(coordinator, users);
    }

    @Test
    void databaseFailureAfterRefreshEndsSession() throws Exception {
        var req = request("/bff/profile");
        when(coordinator.ensureFresh(any(), any(), any())).thenReturn(Optional.of(fixture.view));
        when(users.find(any()))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "private-db"));
        var response = new MockHttpServletResponse();
        filter.doFilter(
                req,
                response,
                (r, s) -> {
                    throw new AssertionError("must stop");
                });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(req.getSession(false)).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void disabledUserAfterRefreshEndsSession() throws Exception {
        var req = request("/bff/profile");
        var old = fixture.view;
        var disabled =
                new AppUserView(
                        old.id(),
                        old.issuer(),
                        old.subject(),
                        old.snapshot(),
                        AppUserStatus.DISABLED,
                        old.roles(),
                        old.createdAt(),
                        old.updatedAt(),
                        old.lastLoginAt(),
                        old.version());
        when(coordinator.ensureFresh(any(), any(), any())).thenReturn(Optional.of(old));
        when(users.find(any())).thenReturn(Optional.of(disabled));
        var response = new MockHttpServletResponse();
        filter.doFilter(
                req,
                response,
                (r, s) -> {
                    throw new AssertionError("must stop");
                });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(req.getSession(false)).isNull();
    }

    MockHttpServletRequest request(String path) {
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext().setAuthentication(fixture.auth);
        var r = new MockHttpServletRequest("GET", path);
        r.setServletPath(path);
        r.setSession(new MockHttpSession());
        return r;
    }
}
