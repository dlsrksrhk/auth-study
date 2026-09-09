package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

final class OidcLoginFailureHandler implements AuthenticationFailureHandler {
    private final ReferenceSecurityProperties properties;
    private final RpSessionCleaner cleaner;

    OidcLoginFailureHandler(ReferenceSecurityProperties properties, ServerProperties server) {
        this.properties = properties;
        cleaner = new RpSessionCleaner(Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure()));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        cleaner.clear(request, response);
        var failure = exception instanceof AppOidcUserService.LocalUserLoginAuthenticationException
                ? URI.create(properties.spaOrigin() + "/login-error?code=local_user_disabled")
                : properties.failureUri();
        response.sendRedirect(failure.toString());
    }
}
