package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;

final class RpSessionCleaner {
    private final boolean secureCookie;

    RpSessionCleaner(boolean secureCookie) { this.secureCookie = secureCookie; }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        request.removeAttribute(CurrentAppUser.class.getName());
        var session = request.getSession(false);
        if (session != null) {
            try {
                synchronized (org.springframework.web.util.WebUtils.getSessionMutex(session)) {
                    OAuthSessionRefreshCoordinator.close(session);
                    session.invalidate();
                }
            } catch (IllegalStateException alreadyInvalid) { /* Already closed. */ }
        }
        if (response.isCommitted()) return;
        // A failed login may already have rotated the ID and queued its cookie.
        var otherCookies = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> !cookie.startsWith("RP_SESSION=")).toList();
        response.setHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("RP_SESSION", "").path("/")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").maxAge(0).build().toString());
        otherCookies.forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
