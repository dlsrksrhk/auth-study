package com.sweet.authstudy.oauth.application;

import java.net.URI;
import java.util.Set;

import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;

public final class OAuthClientCommands {

    private OAuthClientCommands() {
    }

    public record CreateClient(
            String companyCode,
            String displayName,
            boolean publicClient,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            OAuthClientTrust trust) {
    }

    public record UpdateClient(
            String displayName,
            Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            Set<String> scopes,
            OAuthClientTrust trust,
            OAuthClientStatus status,
            long version) {
    }

    public record ClientSecretResult(OAuthClientView client, String oneTimeSecret) {

        @Override
        public String toString() {
            return "ClientSecretResult[client=" + client + ", oneTimeSecret=[REDACTED]]";
        }
    }
}
