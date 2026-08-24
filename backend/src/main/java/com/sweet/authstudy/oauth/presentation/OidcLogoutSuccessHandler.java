package com.sweet.authstudy.oauth.presentation;

import java.io.IOException;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/** Validates and completes RP-Initiated Logout against the current IdP browser session. */
public final class OidcLogoutSuccessHandler implements AuthenticationProvider, AuthenticationSuccessHandler {

    private final JwtDecoder decoder;
    private final OAuthClientRepository clients;
    private final OAuthSecurityProperties properties;
    private final OAuthProtocolEventService events;

    public OidcLogoutSuccessHandler(JwtDecoder decoder, OAuthClientRepository clients,
            OAuthSecurityProperties properties, OAuthProtocolEventService events) {
        this.decoder = decoder;
        this.clients = clients;
        this.properties = properties;
        this.events = events;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OidcLogoutAuthenticationToken request = (OidcLogoutAuthenticationToken) authentication;
        IdpSessionAuthentication session = request.getPrincipal() instanceof IdpSessionAuthentication candidate
                ? candidate : null;
        Jwt jwt = decode(request.getIdTokenHint());
        String clientId = singleAudience(jwt);
        OAuthClient client = clients.findByClientId(clientId)
                .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                .orElseThrow(OidcLogoutSuccessHandler::invalidRequest);
        if (session == null || !session.isAuthenticated()
                || request.getSessionId() == null
                || request.getClientId() == null || !request.getClientId().equals(clientId)
                || !session.sub().toString().equals(jwt.getSubject())
                || !session.sessionBinding().equals(sessionBinding(jwt))
                || session.companyId() == null || session.companyId() != client.companyId()
                || jwt.getClaimAsInstant("auth_time") == null
                || !session.authenticatedAt().truncatedTo(ChronoUnit.SECONDS)
                        .equals(jwt.getClaimAsInstant("auth_time").truncatedTo(ChronoUnit.SECONDS))
                || !validState(request.getState())
                || !registeredRedirect(client, request.getPostLogoutRedirectUri())) {
            throw invalidRequest();
        }
        OidcIdToken idToken = new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(),
                jwt.getExpiresAt(), jwt.getClaims());
        return new OidcLogoutAuthenticationToken(idToken, session, request.getSessionId(),
                clientId, request.getPostLogoutRedirectUri(), request.getState());
    }

    private Jwt decode(String hint) {
        try {
            return decoder.decode(hint);
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private String singleAudience(Jwt jwt) {
        if (jwt.getAudience() == null || jwt.getAudience().size() != 1
                || jwt.getAudience().getFirst() == null || jwt.getAudience().getFirst().isBlank()) {
            throw invalidRequest();
        }
        return jwt.getAudience().getFirst();
    }

    private java.util.UUID sessionBinding(Jwt jwt) {
        try {
            String sid = jwt.getClaimAsString("sid");
            return sid == null ? null : java.util.UUID.fromString(sid);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean registeredRedirect(OAuthClient client, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return clients.findByClientId(client.clientId())
                    .filter(current -> current.status() == OAuthClientStatus.ACTIVE)
                    .filter(current -> current.companyId() == client.companyId())
                    .map(current -> current.allowsPostLogoutRedirect(URI.create(value)))
                    .orElse(false);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean validState(String state) {
        return state == null || state.length() <= 512
                && state.indexOf('\r') < 0 && state.indexOf('\n') < 0;
    }

    private static OAuth2AuthenticationException invalidRequest() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OidcLogoutAuthenticationToken logout = (OidcLogoutAuthenticationToken) authentication;
        IdpSessionAuthentication session = (IdpSessionAuthentication) logout.getPrincipal();
        var context = new OAuthProtocolEventService.Context(logout.getClientId(), session.sub(),
                session.accountId(), session.companyId(), null);
        SecurityContextHolder.clearContext();
        var httpSession = request.getSession(false);
        if (httpSession != null) httpSession.invalidate();
        IdpLoginController.expireSessionCookie(response, properties);
        events.success(OAuthProtocolEvent.EventType.LOGOUT_COMPLETED, context,
                OAuthProtocolEvent.Metadata.from(Map.of(
                        "endpoint", OAuthProtocolEvent.Endpoint.LOGOUT,
                        "session_invalidated", true)));
        URI redirect = URI.create(logout.getPostLogoutRedirectUri());
        String location = logout.getState() == null
                ? redirect.toString()
                : UriComponentsBuilder.fromUri(redirect).queryParam("state", logout.getState())
                        .build().encode().toUriString();
        response.sendRedirect(location);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OidcLogoutAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
