package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.util.Objects;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

public final class RefreshCandidate {
    private final OAuth2AuthorizedClient client;
    private final ExternalIdentityProfile profile;
    public RefreshCandidate(OAuth2AuthorizedClient client, ExternalIdentityProfile profile) {
        this.client = Objects.requireNonNull(client); this.profile = Objects.requireNonNull(profile);
    }
    public OAuth2AuthorizedClient client() { return client; }
    public ExternalIdentityProfile profile() { return profile; }
    @Override public String toString() { return "RefreshCandidate[registrationId=" + client.getClientRegistration().getRegistrationId() + ", subject=" + profile.subject() + "]"; }
}
