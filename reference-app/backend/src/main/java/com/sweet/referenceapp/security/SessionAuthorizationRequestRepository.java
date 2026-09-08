package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** One pending request per session, with state-independent access for the callback guard. */
final class SessionAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private static final String ATTRIBUTE = SessionAuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST";

    OAuth2AuthorizationRequest peek(HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null ? null : (OAuth2AuthorizationRequest) session.getAttribute(ATTRIBUTE);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var pending = peek(request);
        var states = request.getParameterValues("state");
        return pending != null && states != null && states.length == 1 && matches(pending.getState(), states[0]) ? pending : null;
    }

    static boolean matches(String expected, String actual) {
        return expected != null && actual != null && !expected.isEmpty() && !actual.isEmpty()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            var session = request.getSession(false);
            if (session != null) session.removeAttribute(ATTRIBUTE);
        } else {
            request.getSession().setAttribute(ATTRIBUTE, authorizationRequest);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        var pending = loadAuthorizationRequest(request);
        if (pending != null) request.getSession(false).removeAttribute(ATTRIBUTE);
        return pending;
    }
}
