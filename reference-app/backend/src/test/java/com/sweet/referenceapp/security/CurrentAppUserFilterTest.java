package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.application.CurrentAppUserService;
import com.sweet.referenceapp.user.domain.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentAppUserFilterTest {
    private final UUID id = UUID.randomUUID();
    private final CurrentAppUserService users = mock(CurrentAppUserService.class);
    private final CurrentAppUserFilter filter = new CurrentAppUserFilter(users, new RpSessionCleaner(false));
    private final MockHttpServletRequest request = request();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void refreshesAuthoritiesAndRequestViewWithoutChangingSharedSessionContext() throws Exception {
        var original = login();
        var shared = SecurityContextHolder.getContext();
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", shared);
        request.setSession(session);
        var promoted = view("https://idp.example", "subject", AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER, AppRole.APP_ADMIN));
        when(users.find(id)).thenReturn(Optional.of(promoted));
        filter.doFilter(request, response, (req, res) -> {
            var next = (OAuth2AuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            assertThat(next.getAuthorities()).extracting("authority").containsExactly("APP_ADMIN", "APP_USER");
            assertThat(next.getName()).isEqualTo("subject");
            assertThat(next.getAuthorizedClientRegistrationId()).isEqualTo("reference-app");
            assertThat(next.getDetails()).isEqualTo("details");
            assertThat(CurrentAppUser.find(request)).containsSame(promoted);
        });
        verify(users).find(id);
        assertThat(shared.getAuthentication()).isSameAs(original);
        assertThat(original.getAuthorities()).extracting("authority").containsExactly("APP_USER");
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(shared);
    }

    @Test void requestsSharingOriginalAuthenticationKeepIndependentAuthorities() throws Exception {
        var original = login();
        var promoted = view("https://idp.example", "subject", AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER, AppRole.APP_ADMIN));
        when(users.find(id)).thenReturn(Optional.of(promoted), Optional.of(local()));
        var captured = new ArrayList<Authentication>();
        FilterChain chain = (req, res) -> captured.add(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, response, chain);
        SecurityContextHolder.getContext().setAuthentication(original);
        filter.doFilter(request(), new MockHttpServletResponse(), chain);
        assertThat(captured.get(0).getAuthorities()).extracting("authority").contains("APP_ADMIN");
        assertThat(captured.get(1).getAuthorities()).extracting("authority").containsExactly("APP_USER");
        assertThat(original.getAuthorities()).extracting("authority").containsExactly("APP_USER");
        verify(users, times(2)).find(id);
    }

    @Test void missingDisabledOrMismatchedIdentityInvalidateSessionBeforeChain() throws Exception {
        var invalid = List.of(Optional.<AppUserView>empty(),
                Optional.of(view("https://idp.example", "subject", AppUserStatus.DISABLED, Set.of(AppRole.APP_USER))),
                Optional.of(view("https://other.example", "subject", AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER))),
                Optional.of(view("https://idp.example", "other", AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER))));
        for (var result : invalid) {
            login();
            var req = request();
            var res = new MockHttpServletResponse();
            var session = new MockHttpSession();
            req.setSession(session);
            when(users.find(id)).thenReturn(result);
            filter.doFilter(req, res, (r, s) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThat(CurrentAppUser.find(req)).isEmpty();
            });
            assertThat(session.isInvalid()).isTrue();
            assertThat(res.getHeader("Set-Cookie")).contains("RP_SESSION=", "Max-Age=0");
        }
    }

    @Test void oldLocalLookupFailureCannotClearSameSessionNewLogin() throws Exception {
        login();
        var session = new MockHttpSession();
        request.setSession(session);
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
        new LoginGenerationSecurityContextRepository().loadDeferredContext(request).get();
        when(users.find(id)).thenAnswer(call -> {
            var callback = request();
            callback.setSession(session);
            RpLoginGeneration.beginLogin(callback);
            callback.changeSessionId();
            session.setAttribute("new-login", true);
            return Optional.empty();
        });
        filter.doFilter(request, response, (r, s) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute("new-login")).isEqualTo(true);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void legacyAuthenticationIsClearedWithoutDatabaseLookup() throws Exception {
        for (var auth : List.of(UsernamePasswordAuthenticationToken.authenticated("old", "", List.of()),
                new OAuth2AuthenticationToken(oidc(), oidc().getAuthorities(), "reference-app"))) {
            SecurityContextHolder.getContext().setAuthentication(auth);
            var req = request();
            var session = new MockHttpSession();
            req.setSession(session);
            filter.doFilter(req, response, (r, s) -> assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
            assertThat(session.isInvalid()).isTrue();
        }
        verifyNoInteractions(users);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test void anonymousRequestsNeitherReadDatabaseNorCreateSession() throws Exception {
        filter.doFilter(request, response, (r, s) -> assertThat(CurrentAppUser.find(request)).isEmpty());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var next = request();
        filter.doFilter(next, response, (r, s) -> assertThat(CurrentAppUser.find(next)).isEmpty());
        verifyNoInteractions(users);
        assertThat(request.getSession(false)).isNull();
        assertThat(next.getSession(false)).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test void databaseFailureStopsRequestWith503AndPreservesSession() throws Exception {
        var original = login();
        var session = new MockHttpSession();
        request.setSession(session);
        when(users.find(id)).thenThrow(new DataAccessResourceFailureException("secret"));
        var chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(session.isInvalid()).isFalse();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(original.getAuthorities()).extracting("authority").containsExactly("APP_USER");
        verifyNoInteractions(chain);
    }

    @Test void downstreamDatabaseExceptionsAreNotMisclassifiedAsLookupFailures() {
        login();
        when(users.find(id)).thenReturn(Optional.of(local()));
        var failure = new DataAccessResourceFailureException("controller");
        assertThatThrownBy(() -> filter.doFilter(request, response, (r, s) -> { throw failure; })).isSameAs(failure);
    }

    @Test void skipsNonBffAndErrorOrAsyncDispatches() throws Exception {
        login();
        for (var path : List.of("/login/oauth2/code/reference-app", "/other", "/bff-other")) {
            var req = request();
            req.setServletPath(path);
            filter.doFilter(req, response, (r, s) -> {});
        }
        for (var type : List.of(DispatcherType.ERROR, DispatcherType.ASYNC)) {
            var req = request();
            req.setDispatcherType(type);
            filter.doFilter(req, response, (r, s) -> {});
        }
        verifyNoInteractions(users);
    }

    @Test void logoutAndContinuationSkipDatabaseEvenDuringOutage() throws Exception {
        login();
        for (var path : List.of("/bff/logout", "/bff/logout/identity-provider", "/bff/logout/continue/ticket")) {
            var req = request(); req.setMethod("POST"); req.setServletPath(path);
            filter.doFilter(req, response, (r, s) -> {});
        }
        verifyNoInteractions(users);
    }

    private OAuth2AuthenticationToken login() {
        var principal = new AppOidcUser(oidc(), local());
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "reference-app");
        authentication.setDetails("details");
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
    private DefaultOidcUser oidc() {
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")),
                new OidcIdToken("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(600), Map.of("iss", "https://idp.example", "sub", "subject")));
    }
    private AppUserView local() { return view("https://idp.example", "subject", AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER)); }
    private AppUserView view(String issuer, String subject, AppUserStatus status, Set<AppRole> roles) {
        return new AppUserView(id, issuer, subject, new ExternalUserSnapshot(null, null, null, null, Set.of()), status, roles, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 0);
    }
    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/bff/profile");
        request.setServletPath("/bff/profile");
        return request;
    }
}
