package com.sweet.authstudy.oauth.infrastructure;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountStatus;
import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCodeExchangeBinding;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import com.sweet.authstudy.oauth.presentation.IdpSessionAuthentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Maps Spring Authorization Server protocol objects to the deliberately small persistence model.
 * Raw token values exist only on the Spring side and are hashed before a domain object is created.
 */
@Component
public final class OAuthAuthorizationMapper {

    private static final String CODE_CHALLENGE = "code_challenge";
    private static final String CODE_CHALLENGE_METHOD = "code_challenge_method";
    private static final String NONCE = "nonce";
    private static final String STATE_TOKEN_TYPE = "state";

    private final OAuthClientRepository clients;
    private final OAuthSubjectRepository subjects;
    private final AccountRepository accounts;
    private final OAuthSecurityProperties properties;
    private final Clock clock;

    public OAuthAuthorizationMapper(OAuthClientRepository clients, OAuthSubjectRepository subjects,
            AccountRepository accounts, OAuthSecurityProperties properties, Clock clock) {
        this.clients = clients;
        this.subjects = subjects;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    com.sweet.authstudy.oauth.domain.OAuthAuthorization toDomain(
            OAuth2Authorization source,
            com.sweet.authstudy.oauth.domain.OAuthAuthorization existing) {
        Objects.requireNonNull(source, "authorization");
        long registeredClientId = parseId(source.getRegisteredClientId(), "registered client id");
        long accountId = parseId(source.getPrincipalName(), "principal account id");
        OAuthClient client = clients.findById(registeredClientId)
                .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Active OAuth client does not exist."));
        OAuthSubject subject = subjects.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("OAuth subject does not exist for principal account id."));
        Account account = accounts.findById(accountId)
                .filter(candidate -> candidate.status() == AccountStatus.ACTIVE)
                .orElseThrow(OAuthAuthorizationMapper::invalidOwnership);
        if (account.companyId() == null || account.companyId() != client.companyId()) {
            throw invalidOwnership();
        }
        org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest request =
                requiredAuthorizationRequest(source);
        validateRequest(client, request);
        com.sweet.authstudy.oauth.domain.OAuthAuthorization.Attributes attributes =
                authorizationAttributes(source.getPrincipalName(), request);
        String serverStateHash = serverStateHash(source);

        Instant createdAt = existing == null ? earliestIssuedAt(source) : existing.createdAt();
        Instant authenticatedAt = existing == null
                ? initialAuthenticatedAt(source, accountId, createdAt)
                : existing.authenticatedAt();
        Instant expiresAt = existing == null
                ? createdAt.plus(properties.refreshTokenTtl())
                : existing.expiresAt();
        com.sweet.authstudy.oauth.domain.OAuthAuthorization mapped = existing == null
                ? com.sweet.authstudy.oauth.domain.OAuthAuthorization.create(
                        source.getId(),
                        com.sweet.authstudy.oauth.domain.OAuthAuthorization.Ownership.verified(
                                client, subject, accountId, account.companyId()),
                        source.getAuthorizationGrantType().getValue(), source.getAuthorizedScopes(),
                        attributes, serverStateHash, authenticatedAt, createdAt, expiresAt)
                : com.sweet.authstudy.oauth.domain.OAuthAuthorization.restore(
                        existing.id(), existing.registeredClientId(), existing.subject(),
                        existing.principalAccountId(), existing.companyId(),
                        source.getAuthorizationGrantType().getValue(), source.getAuthorizedScopes(),
                        attributes, serverStateHash, existing.authenticatedAt(), existing.status(),
                        existing.revocationReason(), existing.createdAt(), existing.expiresAt(),
                        existing.revokedAt(), existing.idTokenEvidence().orElse(null),
                        null, null, null);

        mapAuthorizationCode(source, request, existing).ifPresent(mapped::attachAuthorizationCode);
        mapAccessToken(source, existing).ifPresent(mapped::attachAccessToken);
        mapRefreshToken(source, existing).ifPresent(mapped::attachRefreshToken);
        return mapped;
    }

    OAuth2Authorization toSpring(com.sweet.authstudy.oauth.domain.OAuthAuthorization source,
            String lookedUpToken, String lookedUpTokenType) {
        return toSpring(source, lookedUpToken, lookedUpTokenType, false);
    }

    OAuth2Authorization toSpring(com.sweet.authstudy.oauth.domain.OAuthAuthorization source,
            String lookedUpToken, String lookedUpTokenType, boolean activeConsumedCode) {
        return toSpring(source, lookedUpToken, lookedUpTokenType, activeConsumedCode, false);
    }

    OAuth2Authorization toSpringForUserInfo(
            com.sweet.authstudy.oauth.domain.OAuthAuthorization source, String rawAccessToken) {
        return toSpring(source, rawAccessToken,
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN.getValue(),
                false, true);
    }

    private OAuth2Authorization toSpring(com.sweet.authstudy.oauth.domain.OAuthAuthorization source,
            String lookedUpToken, String lookedUpTokenType, boolean activeConsumedCode,
            boolean userInfoLookup) {
        Objects.requireNonNull(source, "authorization");
        if (source.status() != com.sweet.authstudy.oauth.domain.OAuthAuthorization.Status.ACTIVE
                || source.revokedAt() != null) {
            return null;
        }
        OAuthClient client = clients.findById(source.registeredClientId())
                .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                .orElse(null);
        if (client == null) return null;

        RegisteredClient registeredClient = registeredClient(client);
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
                .id(source.id())
                .principalName(source.attributes().principalName())
                .authorizationGrantType(new AuthorizationGrantType(source.authorizationGrantType()))
                .authorizedScopes(source.authorizedScopes());

        OAuth2AuthorizationRequest request = authorizationRequest(source, client);
        builder.attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .attribute(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                        source.attributes().principalName(), "N/A", List.of()));
        if (STATE_TOKEN_TYPE.equals(lookedUpTokenType) && lookedUpToken != null) {
            builder.attribute("state", lookedUpToken);
        }

        source.authorizationCode().ifPresent(code -> {
            String value = tokenValue(code.codeHash(), lookedUpToken, lookedUpTokenType, "code");
            org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode springCode =
                    new org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode(
                            value, code.issuedAt(), code.expiresAt());
            builder.token(springCode, metadata -> {
                if (code.usedAt() != null && !activeConsumedCode) {
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true);
                }
            });
        });
        source.accessToken().ifPresent(token -> {
            String value = tokenValue(token.accessTokenHash(), lookedUpToken, lookedUpTokenType,
                    org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN.getValue());
            OAuth2AccessToken springToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, value, token.issuedAt(), token.expiresAt(),
                    source.authorizedScopes());
            builder.token(springToken, metadata -> {
                metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                        Map.of("jti", token.jti(), "aud", List.of(token.audience())));
                if (token.revokedAt() != null) {
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true);
                }
            });
        });
        if (userInfoLookup && source.authorizedScopes().contains("openid")
                && source.accessToken().isPresent() && source.idTokenEvidence().isPresent()) {
            OAuthSubject currentSubject = subjects.findByAccountId(source.principalAccountId()).orElse(null);
            if (currentSubject != null
                    && currentSubject.accountId() == source.principalAccountId()
                    && currentSubject.subject().equals(source.subject())) {
                com.sweet.authstudy.oauth.domain.OAuthAuthorization.IdTokenEvidence evidence =
                        source.idTokenEvidence().orElseThrow();
                builder.token(OidcIdToken.withTokenValue("userinfo-metadata:" + source.id())
                        .issuer(properties.issuer().toString())
                        .subject(currentSubject.subject().toString())
                        .audience(List.of(client.clientId()))
                        .issuedAt(evidence.issuedAt())
                        .expiresAt(evidence.expiresAt())
                        .build());
            }
        }
        source.refreshToken().ifPresent(token -> {
            String value = tokenValue(token.refreshTokenHash(), lookedUpToken, lookedUpTokenType,
                    org.springframework.security.oauth2.server.authorization.OAuth2TokenType.REFRESH_TOKEN.getValue());
            OAuth2RefreshToken springToken = new OAuth2RefreshToken(value, token.issuedAt(), token.expiresAt());
            builder.token(springToken, metadata -> {
                if (token.usedAt() != null || token.revokedAt() != null) {
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true);
                }
            });
        });
        return builder.build();
    }

    static long parseId(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a positive numeric account id.", exception);
        }
    }

    private Instant initialAuthenticatedAt(
            OAuth2Authorization source, long accountId, Instant fallback) {
        Object principal = source.getAttribute(Principal.class.getName());
        if (principal instanceof IdpSessionAuthentication idp
                && idp.accountId() == accountId
                && idp.getName().equals(source.getPrincipalName())) {
            return idp.authenticatedAt();
        }
        return fallback;
    }

    static String sha256(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Token value must not be blank.");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawValue.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private java.util.Optional<OAuthAuthorizationCode> mapAuthorizationCode(
            OAuth2Authorization source,
            org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest request,
            com.sweet.authstudy.oauth.domain.OAuthAuthorization existing) {
        OAuth2Authorization.Token<org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode> token =
                source.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class);
        if (token == null) return java.util.Optional.empty();
        String hash = hashOrExisting(token.getToken().getTokenValue(),
                existing == null ? null : existing.authorizationCode().orElse(null),
                existingCode -> existingCode.codeHash());
        OAuthAuthorizationCode previous = existing == null
                ? null : existing.authorizationCode().orElse(null);
        Instant expiresAt = token.getToken().getIssuedAt().plus(properties.authorizationCodeTtl());
        return java.util.Optional.of(OAuthAuthorizationCode.restore(
                previous != null && previous.codeHash().equals(hash) ? previous.id() : null,
                source.getId(), hash, URI.create(request.getRedirectUri()),
                request.getAdditionalParameters().get(CODE_CHALLENGE).toString(),
                nullableString(request.getAdditionalParameters().get(NONCE)),
                token.getToken().getIssuedAt(), expiresAt,
                previous != null && previous.codeHash().equals(hash) ? previous.usedAt() : null));
    }

    private java.util.Optional<OAuthAccessToken> mapAccessToken(
            OAuth2Authorization source,
            com.sweet.authstudy.oauth.domain.OAuthAuthorization existing) {
        OAuth2Authorization.Token<OAuth2AccessToken> token = source.getAccessToken();
        if (token == null) return java.util.Optional.empty();
        OAuthAccessToken previous = existing == null ? null : existing.accessToken().orElse(null);
        String hash = hashOrExisting(token.getToken().getTokenValue(), previous,
                OAuthAccessToken::accessTokenHash);
        Map<String, Object> claims = token.getClaims();
        String jti = claims == null ? null : nullableString(claims.get("jti"));
        String audience = claims == null ? null : firstAudience(claims.get("aud"));
        if (jti == null || jti.isBlank() || audience == null) {
            throw new IllegalArgumentException("Access token claims must contain jti and aud.");
        }
        Instant revokedAt = previous != null && previous.accessTokenHash().equals(hash)
                ? previous.revokedAt() : null;
        Long id = previous != null && previous.accessTokenHash().equals(hash) ? previous.id() : null;
        return java.util.Optional.of(OAuthAccessToken.restore(
                id, source.getId(), hash, jti, audience, token.getToken().getIssuedAt(),
                token.getToken().getExpiresAt(), revokedAt));
    }

    private java.util.Optional<OAuthRefreshToken> mapRefreshToken(
            OAuth2Authorization source,
            com.sweet.authstudy.oauth.domain.OAuthAuthorization existing) {
        OAuth2Authorization.Token<OAuth2RefreshToken> token = source.getRefreshToken();
        if (token == null) return java.util.Optional.empty();
        OAuthRefreshToken previous = existing == null ? null : existing.refreshToken().orElse(null);
        String hash = hashOrExisting(token.getToken().getTokenValue(), previous,
                OAuthRefreshToken::refreshTokenHash);
        boolean same = previous != null && previous.refreshTokenHash().equals(hash);
        return java.util.Optional.of(OAuthRefreshToken.restore(
                same ? previous.id() : null, source.getId(), hash,
                same ? previous.familyId() : UUID.randomUUID(), token.getToken().getIssuedAt(),
                token.getToken().getExpiresAt(), same ? previous.usedAt() : null,
                same ? previous.revokedAt() : null, same ? previous.successorId() : null));
    }

    OAuthAuthorizationRepository.CodeFinalization codeFinalization(
            OAuth2Authorization source, OAuthAuthorizationCodeExchangeBinding consumedBinding,
            String authenticatedSecretHash) {
        OAuthAccessToken accessToken = mapAccessToken(source, null)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Authorization-code finalization requires an access token."));
        OAuthRefreshToken refreshToken = mapRefreshToken(source, null).orElse(null);
        OAuthAuthorizationRepository.IdTokenCandidate idTokenCandidate = idTokenCandidate(source);
        OAuthAuthorizationCodeExchangeBinding candidateBinding = codeExchangeBinding(source);
        Set<String> accessTokenScopes = source.getAccessToken().getToken().getScopes();
        return new OAuthAuthorizationRepository.CodeFinalization(
                consumedBinding, candidateBinding, authenticatedSecretHash, accessTokenScopes,
                idTokenCandidate, accessToken, refreshToken);
    }

    private OAuthAuthorizationRepository.IdTokenCandidate idTokenCandidate(OAuth2Authorization source) {
        OAuth2Authorization.Token<OidcIdToken> idToken = source.getToken(OidcIdToken.class);
        boolean openid = source.getAuthorizedScopes().contains("openid");
        if (openid != (idToken != null)) {
            throw new IllegalArgumentException("ID token issuance does not match the authorized scopes.");
        }
        if (idToken == null) return null;
        OidcIdToken token = idToken.getToken();
        return new OAuthAuthorizationRepository.IdTokenCandidate(
                token.getSubject(), Set.copyOf(token.getAudience()),
                token.getIssuedAt(), token.getExpiresAt());
    }

    OAuthAuthorizationCodeExchangeBinding codeExchangeBinding(OAuth2Authorization source) {
        Objects.requireNonNull(source, "authorization");
        var code = source.getToken(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class);
        if (code == null) {
            throw new IllegalArgumentException("Authorization-code token is required.");
        }
        OAuth2AuthorizationRequest request = requiredAuthorizationRequest(source);
        long registeredClientId;
        try {
            registeredClientId = Long.parseLong(source.getRegisteredClientId());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Authorization-code finalization client is invalid.", exception);
        }
        Object challenge = request.getAdditionalParameters().get(CODE_CHALLENGE);
        Object method = request.getAdditionalParameters().get(CODE_CHALLENGE_METHOD);
        Object nonce = request.getAdditionalParameters().get(NONCE);
        if (!(challenge instanceof String challengeValue)
                || !(method instanceof String methodValue)
                || nonce != null && !(nonce instanceof String)) {
            throw new IllegalArgumentException("Authorization-code request binding is invalid.");
        }
        return new OAuthAuthorizationCodeExchangeBinding(
                sha256(code.getToken().getTokenValue()), code.getToken().getIssuedAt(),
                code.getToken().getExpiresAt(), source.getId(), registeredClientId,
                source.getPrincipalName(), source.getAuthorizationGrantType().getValue(),
                source.getAuthorizedScopes(),
                new OAuthAuthorizationCodeExchangeBinding.AuthorizationRequest(
                        request.getAuthorizationUri(), request.getClientId(), request.getRedirectUri(),
                        request.getScopes(), request.getState(), (String) nonce,
                        challengeValue, methodValue));
    }

    private <T> String hashOrExisting(String tokenValue, T existing,
            java.util.function.Function<T, String> existingHash) {
        if (existing != null && tokenValue.equals(existingHash.apply(existing))) {
            return tokenValue;
        }
        return sha256(tokenValue);
    }

    private OAuth2AuthorizationRequest requiredAuthorizationRequest(OAuth2Authorization authorization) {
        OAuth2AuthorizationRequest request = authorization.getAttribute(
                OAuth2AuthorizationRequest.class.getName());
        if (request == null) {
            throw new IllegalArgumentException("OAuth2AuthorizationRequest attribute is required.");
        }
        return request;
    }

    private void validateRequest(OAuthClient client, OAuth2AuthorizationRequest request) {
        if (!client.clientId().equals(request.getClientId())) {
            throw new IllegalArgumentException("Authorization request client does not match.");
        }
        if (request.getRedirectUri() == null
                || client.redirectUris().stream().noneMatch(uri -> uri.toString().equals(request.getRedirectUri()))) {
            throw new IllegalArgumentException("Authorization request redirect URI must exactly match.");
        }
        Object challenge = request.getAdditionalParameters().get(CODE_CHALLENGE);
        Object method = request.getAdditionalParameters().get(CODE_CHALLENGE_METHOD);
        if (!(challenge instanceof String value) || !value.matches("[A-Za-z0-9_-]{43}")
                || !"S256".equals(method)) {
            throw new IllegalArgumentException("Authorization request must use PKCE S256.");
        }
    }

    private OAuth2AuthorizationRequest authorizationRequest(
            com.sweet.authstudy.oauth.domain.OAuthAuthorization authorization, OAuthClient client) {
        com.sweet.authstudy.oauth.domain.OAuthAuthorization.AuthorizationRequest request =
                authorization.attributes().authorizationRequest();
        if (request == null) {
            OAuthAuthorizationCode code = authorization.authorizationCode()
                    .orElseThrow(() -> new IllegalStateException(
                            "Allowlisted authorization request metadata is required."));
            request = new com.sweet.authstudy.oauth.domain.OAuthAuthorization.AuthorizationRequest(
                    code.redirectUri().toString(), authorization.authorizedScopes(), null,
                    code.codeChallenge(), "S256", code.nonce());
        }
        com.sweet.authstudy.oauth.domain.OAuthAuthorization.AuthorizationRequest snapshot = request;
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(authorization.attributes().authorizationRequestUri())
                .clientId(client.clientId())
                .redirectUri(snapshot.redirectUri())
                .scopes(snapshot.requestedScopes())
                .state(snapshot.rpState())
                .additionalParameters(parameters -> {
                    parameters.put(CODE_CHALLENGE, snapshot.codeChallenge());
                    parameters.put(CODE_CHALLENGE_METHOD, snapshot.codeChallengeMethod());
                    if (snapshot.nonce() != null) parameters.put(NONCE, snapshot.nonce());
                })
                .build();
    }

    private com.sweet.authstudy.oauth.domain.OAuthAuthorization.Attributes authorizationAttributes(
            String principalName, OAuth2AuthorizationRequest request) {
        return new com.sweet.authstudy.oauth.domain.OAuthAuthorization.Attributes(
                principalName, request.getAuthorizationUri(),
                new com.sweet.authstudy.oauth.domain.OAuthAuthorization.AuthorizationRequest(
                        request.getRedirectUri(), request.getScopes(), request.getState(),
                        request.getAdditionalParameters().get(CODE_CHALLENGE).toString(),
                        request.getAdditionalParameters().get(CODE_CHALLENGE_METHOD).toString(),
                        nullableString(request.getAdditionalParameters().get(NONCE))));
    }

    private String serverStateHash(OAuth2Authorization authorization) {
        if (authorization.getToken(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class) != null) {
            return null;
        }
        String serverState = authorization.getAttribute("state");
        return serverState == null ? null : sha256(serverState);
    }

    private RegisteredClient registeredClient(OAuthClient client) {
        RegisteredClient.Builder builder = RegisteredClient.withId(client.id().toString())
                .clientId(client.clientId())
                .clientAuthenticationMethod(client.publicClient()
                        ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(properties.authorizationCodeTtl())
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .build());
        client.redirectUris().forEach(uri -> builder.redirectUri(uri.toString()));
        client.scopes().forEach(builder::scope);
        return builder.build();
    }

    private Instant earliestIssuedAt(OAuth2Authorization authorization) {
        List<Instant> issued = new ArrayList<>();
        OAuth2Authorization.Token<org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode> code =
                authorization.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class);
        if (code != null) issued.add(code.getToken().getIssuedAt());
        if (authorization.getAccessToken() != null) issued.add(authorization.getAccessToken().getToken().getIssuedAt());
        if (authorization.getRefreshToken() != null) issued.add(authorization.getRefreshToken().getToken().getIssuedAt());
        return issued.stream().filter(Objects::nonNull).min(Instant::compareTo).orElse(clock.instant());
    }

    private String firstAudience(Object value) {
        if (value instanceof String audience && !audience.isBlank()) return audience;
        if (value instanceof Collection<?> audiences) {
            return audiences.stream().filter(Objects::nonNull).map(Object::toString)
                    .filter(audience -> !audience.isBlank()).findFirst().orElse(null);
        }
        return null;
    }

    private String tokenValue(String hash, String lookedUpToken, String lookedUpTokenType,
            String expectedType) {
        return expectedType.equals(lookedUpTokenType) && lookedUpToken != null ? lookedUpToken : hash;
    }

    private String nullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private static IllegalArgumentException invalidOwnership() {
        return new IllegalArgumentException("OAuth authorization ownership is invalid.");
    }
}
