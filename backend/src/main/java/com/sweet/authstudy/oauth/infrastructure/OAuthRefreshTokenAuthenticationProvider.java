package com.sweet.authstudy.oauth.infrastructure;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** Project-owned single-use refresh rotation backed by one PostgreSQL transaction. */
public final class OAuthRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthAuthorizationMapper mapper;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final OAuthSecurityProperties properties;
    private final Clock clock;

    public OAuthRefreshTokenAuthenticationProvider(
            OAuthAuthorizationRepository authorizations,
            OAuthAuthorizationMapper mapper,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            OAuthSecurityProperties properties,
            Clock clock) {
        this.authorizations = authorizations;
        this.mapper = mapper;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2RefreshTokenAuthenticationToken grant =
                (OAuth2RefreshTokenAuthenticationToken) authentication;
        OAuth2ClientAuthenticationToken clientPrincipal = authenticatedClient(grant);
        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();
        Instant exchangedAt = clock.instant();

        OAuthAuthorizationRepository.RefreshRotation<GeneratedResponse> rotation =
                authorizations.rotateRefreshAtomically(
                        OAuthAuthorizationMapper.sha256(grant.getRefreshToken()), exchangedAt,
                        locked -> generate(grant, clientPrincipal, registeredClient, locked, exchangedAt));
        if (rotation.status() != OAuthAuthorizationRepository.RefreshRotationStatus.ROTATED) {
            throw invalidGrant();
        }
        GeneratedResponse response = rotation.result().orElseThrow();
        return new OAuth2AccessTokenAuthenticationToken(
                registeredClient, clientPrincipal, response.accessToken(), response.refreshToken());
    }

    private Optional<OAuthAuthorizationRepository.RefreshSuccess<GeneratedResponse>> generate(
            OAuth2RefreshTokenAuthenticationToken grant,
            OAuth2ClientAuthenticationToken clientPrincipal,
            RegisteredClient registeredClient,
            OAuthAuthorizationRepository.LockedRefreshExchange locked,
            Instant exchangedAt) {
        Set<String> scopes = grant.getScopes().isEmpty()
                ? locked.current().authorizedScopes() : Set.copyOf(grant.getScopes());
        if (!currentClientMatches(clientPrincipal, registeredClient, locked, exchangedAt)
                || !locked.principalActive()
                || !locked.consentActive()
                || !locked.authorization().activeAt(exchangedAt)
                || !locked.current().authorizedScopes().containsAll(scopes)
                || !locked.client().scopes().containsAll(scopes)) {
            return Optional.empty();
        }
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization springAuthorization =
                mapper.toSpring(locked.authorization(), grant.getRefreshToken(),
                        OAuth2TokenType.REFRESH_TOKEN.getValue());
        if (springAuthorization == null) return Optional.empty();
        Authentication principal = springAuthorization.getAttribute(Principal.class.getName());
        if (principal == null) return Optional.empty();

        DefaultOAuth2TokenContext.Builder context = DefaultOAuth2TokenContext.builder()
                .registeredClient(registeredClient)
                .principal(principal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorization(springAuthorization)
                .authorizedScopes(scopes)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrant(grant);

        OAuth2Token generatedAccess = tokenGenerator.generate(
                context.tokenType(OAuth2TokenType.ACCESS_TOKEN).build());
        if (generatedAccess == null || generatedAccess.getIssuedAt() == null
                || generatedAccess.getExpiresAt() == null) {
            throw serverError("The token generator failed to generate an access token.");
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, generatedAccess.getTokenValue(),
                generatedAccess.getIssuedAt(), generatedAccess.getExpiresAt(), scopes);

        OAuth2Token generatedRefresh = tokenGenerator.generate(
                context.tokenType(OAuth2TokenType.REFRESH_TOKEN).build());
        if (!(generatedRefresh instanceof OAuth2RefreshToken refresh)) {
            throw serverError("The token generator failed to generate a refresh token.");
        }
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                refresh.getTokenValue(), generatedAccess.getIssuedAt(), locked.current().expiresAt());

        Map<String, Object> claims = generatedAccess instanceof ClaimAccessor accessor
                ? accessor.getClaims() : Map.of();
        String jti = text(claims.get("jti"));
        String audience = firstAudience(claims.get("aud"));
        if (jti == null || audience == null) {
            throw serverError("The access token is missing required claims.");
        }
        OAuthAccessToken persistedAccess = OAuthAccessToken.issue(
                locked.authorization().id(), OAuthAuthorizationMapper.sha256(accessToken.getTokenValue()),
                jti, audience, scopes, accessToken.getIssuedAt(), accessToken.getExpiresAt());
        OAuthRefreshToken successor = OAuthRefreshToken.issue(
                locked.authorization().id(), OAuthAuthorizationMapper.sha256(refreshToken.getTokenValue()),
                locked.current().familyId(), scopes, refreshToken.getIssuedAt(), refreshToken.getExpiresAt());
        return Optional.of(new OAuthAuthorizationRepository.RefreshSuccess<>(
                persistedAccess, successor, new GeneratedResponse(accessToken, refreshToken)));
    }

    private boolean currentClientMatches(
            OAuth2ClientAuthenticationToken clientPrincipal,
            RegisteredClient registeredClient,
            OAuthAuthorizationRepository.LockedRefreshExchange locked,
            Instant now) {
        if (locked.client().status() != OAuthClientStatus.ACTIVE
                || locked.client().id() == null
                || !locked.client().id().toString().equals(registeredClient.getId())
                || !locked.client().clientId().equals(registeredClient.getClientId())
                || locked.authorization().registeredClientId() != locked.client().id()) {
            return false;
        }
        if (locked.client().publicClient()) {
            return ClientAuthenticationMethod.NONE.equals(clientPrincipal.getClientAuthenticationMethod());
        }
        String authenticatedSecretHash = registeredClient.getClientSecret();
        return authenticatedSecretHash != null && locked.client().secrets().stream()
                .filter(secret -> secret.revokedAt() == null)
                .filter(secret -> secret.expiresAt() == null || secret.expiresAt().isAfter(now))
                .map(OAuthClientSecret::secretHash)
                .anyMatch(authenticatedSecretHash::equals);
    }

    private OAuth2ClientAuthenticationToken authenticatedClient(
            OAuth2RefreshTokenAuthenticationToken grant) {
        if (!(grant.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                || !client.isAuthenticated() || client.getRegisteredClient() == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return client;
    }

    private String firstAudience(Object value) {
        if (value instanceof String audience && !audience.isBlank()) return audience;
        if (value instanceof Collection<?> audiences) {
            return audiences.stream().filter(java.util.Objects::nonNull).map(Object::toString)
                    .filter(audience -> !audience.isBlank()).findFirst().orElse(null);
        }
        return null;
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT, "Invalid refresh token grant.", null));
    }

    private OAuth2AuthenticationException serverError(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR, description, null));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private record GeneratedResponse(OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) { }
}
