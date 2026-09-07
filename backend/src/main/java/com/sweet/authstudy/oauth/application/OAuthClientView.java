package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

public record OAuthClientView(
        String companyCode,
        String clientId,
        String displayName,
        OAuthClientStatus status,
        OAuthClientTrust trust,
        boolean publicClient,
        Set<URI> redirectUris,
        Set<URI> postLogoutRedirectUris,
        Set<String> scopes,
        String activeSecretHint,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public OAuthClientView {
        redirectUris = Set.copyOf(redirectUris);
        postLogoutRedirectUris = Set.copyOf(postLogoutRedirectUris);
        scopes = Set.copyOf(scopes);
    }

    static OAuthClientView from(OAuthClient client, String companyCode) {
        String activeHint = client.secrets().stream()
                .filter(secret -> secret.revokedAt() == null)
                .map(secret -> secret.secretHint())
                .findFirst()
                .orElse(null);
        return new OAuthClientView(
                companyCode, client.clientId(), client.displayName(), client.status(), client.trust(),
                client.publicClient(), client.redirectUris(), client.postLogoutRedirectUris(),
                client.scopes(), activeHint, client.version(), client.createdAt(), client.updatedAt());
    }
}
