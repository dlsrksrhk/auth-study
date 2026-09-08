package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class PkceAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    PkceAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) { return delegate.resolve(request); }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return delegate.resolve(request, registrationId);
    }
}
