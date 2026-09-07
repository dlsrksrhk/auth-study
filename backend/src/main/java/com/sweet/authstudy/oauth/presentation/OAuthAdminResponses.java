package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.oauth.application.OAuthClientView;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

public final class OAuthAdminResponses {
    private OAuthAdminResponses() {}

    public record ClientResponse(String companyCode, String clientId, String displayName,
            OAuthClientStatus status, OAuthClientTrust trust, boolean publicClient,
            Set<URI> redirectUris, Set<URI> postLogoutRedirectUris, Set<String> scopes,
            String activeSecretHint, long version, Instant createdAt, Instant updatedAt) {
        public static ClientResponse from(OAuthClientView view) {
            return new ClientResponse(view.companyCode(), view.clientId(), view.displayName(),
                    view.status(), view.trust(), view.publicClient(), view.redirectUris(),
                    view.postLogoutRedirectUris(), view.scopes(), view.activeSecretHint(),
                    view.version(), view.createdAt(), view.updatedAt());
        }
    }

    public record OneTimeClientSecretResponse(ClientResponse client, String oneTimeSecret) {}
}
