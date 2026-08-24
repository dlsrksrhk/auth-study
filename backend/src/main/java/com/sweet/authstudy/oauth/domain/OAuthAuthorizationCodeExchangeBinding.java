package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, allowlisted identity of one consumed authorization-code exchange.
 */
public record OAuthAuthorizationCodeExchangeBinding(
        String codeHash,
        Instant codeIssuedAt,
        Instant codeExpiresAt,
        String authorizationId,
        long registeredClientId,
        String principalName,
        String authorizationGrantType,
        Set<String> authorizedScopes,
        AuthorizationRequest authorizationRequest) {

    public OAuthAuthorizationCodeExchangeBinding {
        if (codeHash == null || !codeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("codeHash must be a lowercase SHA-256 hash.");
        }
        codeIssuedAt = Objects.requireNonNull(codeIssuedAt, "codeIssuedAt");
        codeExpiresAt = Objects.requireNonNull(codeExpiresAt, "codeExpiresAt");
        authorizationId = OAuthRefreshToken.requireText(authorizationId, "authorizationId");
        if (registeredClientId <= 0) {
            throw new IllegalArgumentException("registeredClientId must be positive.");
        }
        principalName = OAuthRefreshToken.requireText(principalName, "principalName");
        authorizationGrantType = OAuthRefreshToken.requireText(
                authorizationGrantType, "authorizationGrantType");
        authorizedScopes = immutableScopes(authorizedScopes, "authorizedScopes");
        authorizationRequest = Objects.requireNonNull(authorizationRequest, "authorizationRequest");
    }

    public static OAuthAuthorizationCodeExchangeBinding captureLocked(
            OAuthAuthorizationCode code, OAuthAuthorization authorization, OAuthClient client) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(client, "client");
        OAuthAuthorization.AuthorizationRequest request = authorization.attributes().authorizationRequest();
        if (request == null) {
            throw new IllegalArgumentException("Allowlisted authorization request is required.");
        }
        return new OAuthAuthorizationCodeExchangeBinding(
                code.codeHash(), code.issuedAt(), code.expiresAt(), authorization.id(),
                authorization.registeredClientId(), authorization.attributes().principalName(),
                authorization.authorizationGrantType(), authorization.authorizedScopes(),
                new AuthorizationRequest(
                        authorization.attributes().authorizationRequestUri(), client.clientId(),
                        request.redirectUri(), request.requestedScopes(), request.rpState(), request.nonce(),
                        request.codeChallenge(), request.codeChallengeMethod()));
    }

    public record AuthorizationRequest(
            String authorizationUri,
            String clientId,
            String redirectUri,
            Set<String> requestedScopes,
            String rpState,
            String nonce,
            String codeChallenge,
            String codeChallengeMethod) {

        public AuthorizationRequest {
            authorizationUri = OAuthRefreshToken.requireText(authorizationUri, "authorizationUri");
            clientId = OAuthRefreshToken.requireText(clientId, "clientId");
            redirectUri = OAuthRefreshToken.requireText(redirectUri, "redirectUri");
            requestedScopes = immutableScopes(requestedScopes, "requestedScopes");
            codeChallenge = OAuthRefreshToken.requireText(codeChallenge, "codeChallenge");
            codeChallengeMethod = OAuthRefreshToken.requireText(
                    codeChallengeMethod, "codeChallengeMethod");
        }
    }

    private static Set<String> immutableScopes(Set<String> scopes, String name) {
        Objects.requireNonNull(scopes, name);
        scopes.forEach(scope -> OAuthRefreshToken.requireText(scope, name + " element"));
        return Set.copyOf(scopes);
    }
}
