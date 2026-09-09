package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

final class OidcLoginFailureHandler implements AuthenticationFailureHandler {
    private static final String FAILURE_RESPONSE_STARTED = OidcLoginFailureHandler.class.getName() + ".STARTED";
    private final ReferenceSecurityProperties properties;
    private final RpSessionCleaner cleaner;

    OidcLoginFailureHandler(ReferenceSecurityProperties properties, ServerProperties server) {
        this.properties = properties;
        cleaner = new RpSessionCleaner(Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure()));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        request.setAttribute(FAILURE_RESPONSE_STARTED, Boolean.TRUE);
        clearSession(request, response);
        var failure = exception instanceof AppOidcUserService.LocalUserLoginAuthenticationException
                ? URI.create(properties.spaOrigin() + "/login-error?code=local_user_disabled")
                : properties.failureUri();
        response.sendRedirect(failure.toString());
    }

    void clearSession(HttpServletRequest request, HttpServletResponse response) {
        cleaner.clear(request, response);
    }

    boolean failureResponseStarted(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(FAILURE_RESPONSE_STARTED));
    }
}
