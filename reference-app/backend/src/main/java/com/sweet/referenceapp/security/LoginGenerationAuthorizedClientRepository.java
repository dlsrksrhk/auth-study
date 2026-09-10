package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;

/** Keep Spring's session storage; fence callback publication with refresh and logout. */
final class LoginGenerationAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
    private final HttpSessionOAuth2AuthorizedClientRepository delegate = new HttpSessionOAuth2AuthorizedClientRepository();

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
        if (RpLoginGeneration.isLogin(request)) {
            RpLoginGeneration.publishLogin(request, () -> {
                delegate.saveAuthorizedClient(client, principal, request, response);
                // Spring publishes the new authentication after session fixation. Clear the
                // previous context with the client replacement: requests in that gap are anonymous,
                // never authenticated as the previous login with the new login's client.
                var context = SecurityContextHolder.createEmptyContext();
                new LoginGenerationSecurityContextRepository().saveContext(context, request, response);
            });
        } else {
            RpLoginGeneration.withCurrent(request, () -> delegate.saveAuthorizedClient(client, principal, request, response));
        }
    }

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId, Authentication principal,
            HttpServletRequest request) {
        return delegate.loadAuthorizedClient(registrationId, principal, request);
    }

    @Override
    public void removeAuthorizedClient(String registrationId, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
        RpLoginGeneration.withCurrent(request, () -> delegate.removeAuthorizedClient(registrationId, principal, request, response));
    }
}
