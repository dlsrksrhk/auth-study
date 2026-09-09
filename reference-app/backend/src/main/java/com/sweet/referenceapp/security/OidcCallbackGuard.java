package com.sweet.referenceapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

final class OidcCallbackGuard extends OncePerRequestFilter {
    private final URI callback;
    private final SessionAuthorizationRequestRepository requests;
    private final OidcLoginFailureHandler failureHandler;

    OidcCallbackGuard(ReferenceSecurityProperties properties, SessionAuthorizationRequestRepository requests,
            OidcLoginFailureHandler failureHandler) {
        callback = properties.callbackUri();
        this.requests = requests;
        this.failureHandler = failureHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !callback.getPath().equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var pending = requests.peek(request);
        var states = request.getParameterValues("state");
        var code = request.getParameterValues("code");
        var error = request.getParameterValues("error");
        boolean responseShape = (singleNonempty(code) && error == null) || (singleNonempty(error) && code == null);
        if (!"GET".equals(request.getMethod()) || !matchesAddress(request) || !responseShape
                || pending == null || !singleNonempty(states)
                || !SessionAuthorizationRequestRepository.matches(pending.getState(), states[0])) {
            failureHandler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException("oidc_login_failed"));
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException exception) {
            failureHandler.clearSession(request, response);
            throw exception;
        } catch (RuntimeException | ServletException exception) {
            // Provisioning may already be committed; only local authentication is discarded.
            if (response.isCommitted() || failureHandler.failureResponseStarted(request)) {
                failureHandler.clearSession(request, response);
                throw exception;
            }
            failureHandler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException("oidc_login_failed"));
        }
    }

    private boolean matchesAddress(HttpServletRequest request) {
        int expectedPort = callback.getPort() >= 0 ? callback.getPort() : ("https".equals(callback.getScheme()) ? 443 : 80);
        return callback.getScheme().equals(request.getScheme())
                && callback.getHost().equalsIgnoreCase(request.getServerName())
                && expectedPort == request.getServerPort()
                && callback.getRawPath().equals(request.getRequestURI());
    }

    private static boolean singleNonempty(String[] values) {
        return values != null && values.length == 1 && !values[0].isBlank();
    }
}
