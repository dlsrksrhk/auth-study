package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.util.WebUtils;

final class RpSessionCleaner {
    private final boolean secureCookie;
    private final OAuth2AuthorizedClientRepository clients;
    private final OAuthTokenRevoker revoker;

    RpSessionCleaner(boolean secureCookie) { this(secureCookie, null, null); }

    RpSessionCleaner(boolean secureCookie, OAuth2AuthorizedClientRepository clients, OAuthTokenRevoker revoker) {
        this.secureCookie = secureCookie;
        this.clients = clients;
        this.revoker = revoker;
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        OAuth2AuthorizedClient retained = null;
        var session = request.getSession(false);
        if (session == null && !RpLoginGeneration.matches(request, null)) {
            clearRequest(request);
            return;
        }
        if (session != null) {
            try {
                synchronized (WebUtils.getSessionMutex(session)) {
                    if (!RpLoginGeneration.matches(request, session)) {
                        clearRequest(request);
                        return;
                    }
                    OAuthSessionRefreshCoordinator.close(session);
                    try {
                        if (clients != null && authentication instanceof OAuth2AuthenticationToken oauth) {
                            retained = clients.loadAuthorizedClient(oauth.getAuthorizedClientRegistrationId(), oauth, request);
                        }
                    } finally {
                        // Capture and invalidation are one ownership transfer. A concurrent cleaner
                        // cannot capture this retained client again after invalidation.
                        session.invalidate();
                    }
                }
            } catch (IllegalStateException alreadyInvalid) { /* Already closed. */ }
        }
        clearRequest(request);
        if (!response.isCommitted()) {
            // A failed login may already have rotated the ID and queued its cookie.
            var otherCookies = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                    .filter(cookie -> !cookie.startsWith("RP_SESSION=")).toList();
            response.setHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("RP_SESSION", "").path("/")
                    .httpOnly(true).secure(secureCookie).sameSite("Lax").maxAge(0).build().toString());
            otherCookies.forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        // Never hold the termination mutex or leave a usable local session during network cleanup.
        if (retained != null && revoker != null) {
            revoker.revoke(retained.getClientRegistration(), retained.getRefreshToken());
        }
    }

    private static void clearRequest(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        request.removeAttribute(CurrentAppUser.class.getName());
    }
}
