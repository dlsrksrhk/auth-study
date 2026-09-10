package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Optional;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.util.WebUtils;

public final class ReferenceLogoutService {
    private final LogoutHandoffStore handoffs;
    private final OAuth2AuthorizedClientRepository clients;
    private final RpSessionCleaner cleaner;

    ReferenceLogoutService(LogoutHandoffStore handoffs, OAuth2AuthorizedClientRepository clients, RpSessionCleaner cleaner) {
        this.handoffs = handoffs;
        this.clients = clients;
        this.cleaner = cleaner;
    }

    public Optional<URI> logout(HttpServletRequest request, HttpServletResponse response,
            OAuth2AuthenticationToken authentication, boolean identityProvider) {
        try {
            if (!identityProvider) return Optional.empty();
            var session = request.getSession(false);
            if (session == null) throw new ContinuationUnavailableException();
            synchronized (WebUtils.getSessionMutex(session)) {
                // Block publication before releasing the shared termination mutex. The cleaner
                // then captures the retained refresh token and revokes it outside this lock.
                OAuthSessionRefreshCoordinator.close(session);
                var client = clients.loadAuthorizedClient(authentication.getAuthorizedClientRegistrationId(), authentication, request);
                if (client == null || !(authentication.getPrincipal() instanceof OidcUser principal))
                    throw new ContinuationUnavailableException();
                String ticket = handoffs.issue(principal.getIdToken().getTokenValue(), client.getClientRegistration().getClientId());
                return Optional.of(URI.create("/bff/logout/continue/" + ticket));
            }
        } catch (RuntimeException unavailable) {
            // Do not retain exceptions containing token-bearing payloads or URIs.
            throw new ContinuationUnavailableException();
        } finally {
            cleaner.clear(request, response);
        }
    }

    public static final class ContinuationUnavailableException extends RuntimeException {
        public ContinuationUnavailableException() { super("Logout continuation unavailable"); }
    }
}
