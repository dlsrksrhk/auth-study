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
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("RP_SESSION", "").path("/")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").maxAge(0).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
