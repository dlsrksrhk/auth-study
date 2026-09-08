package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

final class OidcLoginFailureHandler implements AuthenticationFailureHandler {
    private final ReferenceSecurityProperties properties;
    private final boolean secureCookie;

    OidcLoginFailureHandler(ReferenceSecurityProperties properties, ServerProperties server) {
        this.properties = properties;
        secureCookie = Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("RP_SESSION", "").path("/")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").maxAge(0).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.sendRedirect(properties.failureUri().toString());
    }
}
