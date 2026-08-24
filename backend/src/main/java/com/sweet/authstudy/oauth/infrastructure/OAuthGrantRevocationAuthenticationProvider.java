package com.sweet.authstudy.oauth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;

/** RFC 7009 provider that revokes the selected authorization grant without touching the IdP session. */
public final class OAuthGrantRevocationAuthenticationProvider implements AuthenticationProvider {

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthProtocolEventService events;
    private final Clock clock;

    public OAuthGrantRevocationAuthenticationProvider(OAuthAuthorizationRepository authorizations,
            OAuthProtocolEventService events, Clock clock) {
        this.authorizations = authorizations;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2TokenRevocationAuthenticationToken request =
                (OAuth2TokenRevocationAuthenticationToken) authentication;
        OAuth2ClientAuthenticationToken client = request.getPrincipal()
                instanceof OAuth2ClientAuthenticationToken candidate ? candidate : null;
        Instant now = clock.instant();
        if (client != null && client.isAuthenticated() && client.getRegisteredClient() != null) {
            Optional<String> authorizationId = authorizationId(request.getToken());
            authorizationId.flatMap(authorizations::findById)
                    .filter(authorization -> ownedBy(authorization, client))
                    .ifPresent(authorization -> revoke(authorization, client, now));
        }
        // Unknown, expired and foreign tokens are deliberately indistinguishable per RFC 7009.
        return new OAuth2TokenRevocationAuthenticationToken(
                new OAuth2RefreshToken(request.getToken(), now, now.plusMillis(1)), client);
    }

    private Optional<String> authorizationId(String rawToken) {
        String hash = sha256(rawToken);
        return authorizations.findByRefreshTokenHash(hash)
                .map(token -> token.authorizationId())
                .or(() -> authorizations.findByAccessTokenHash(hash)
                        .map(token -> token.authorizationId()));
    }

    private boolean ownedBy(OAuthAuthorization authorization, OAuth2ClientAuthenticationToken client) {
        try {
            return authorization.registeredClientId()
                    == Long.parseLong(client.getRegisteredClient().getId());
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void revoke(OAuthAuthorization authorization, OAuth2ClientAuthenticationToken client, Instant now) {
        try {
            authorizations.revokeAuthorization(authorization.id(), now, () ->
                    events.successRequired(OAuthProtocolEvent.EventType.AUTHORIZATION_REVOKED,
                        new OAuthProtocolEventService.Context(client.getRegisteredClient().getClientId(),
                                authorization.subject(), authorization.principalAccountId(),
                                authorization.companyId(), authorization.id()),
                        OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                                "endpoint", OAuthProtocolEvent.Endpoint.REVOCATION,
                                "authentication_method", authenticationMethod(client)))));
        } catch (OAuthProtocolEventService.RequiredEventPersistenceException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR));
        }
    }

    private OAuthProtocolEvent.AuthenticationMethod authenticationMethod(
            OAuth2ClientAuthenticationToken client) {
        if (org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE.equals(
                client.getClientAuthenticationMethod())) {
            return OAuthProtocolEvent.AuthenticationMethod.NONE;
        }
        if (org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(
                client.getClientAuthenticationMethod())) {
            return OAuthProtocolEvent.AuthenticationMethod.CLIENT_SECRET_POST;
        }
        return OAuthProtocolEvent.AuthenticationMethod.CLIENT_SECRET_BASIC;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
