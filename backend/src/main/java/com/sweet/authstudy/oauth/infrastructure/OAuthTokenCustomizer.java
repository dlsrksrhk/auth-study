package com.sweet.authstudy.oauth.infrastructure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.application.OAuthSubjectService;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthSigningKey;
import com.sweet.authstudy.oauth.domain.OAuthSigningKeyRepository;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

@Component
public class OAuthTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final OAuthSigningKeyRepository signingKeys;
    private final OAuthAuthorizationRepository authorizations;
    private final OAuthSubjectService subjects;
    private final OAuthSecurityProperties properties;

    public OAuthTokenCustomizer(OAuthSigningKeyRepository signingKeys,
            OAuthAuthorizationRepository authorizations, OAuthSubjectService subjects,
            OAuthSecurityProperties properties) {
        this.signingKeys = signingKeys;
        this.authorizations = authorizations;
        this.subjects = subjects;
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        OAuthSigningKey active = signingKeys.findActive().orElse(null);
        if (active == null) {
            return;
        }
        context.getJwsHeader().algorithm(SignatureAlgorithm.RS256);
        context.getJwsHeader().keyId(active.kid());

        if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            customizeIdToken(context);
        } else if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            customizeAccessToken(context);
        }
    }

    private void customizeIdToken(JwtEncodingContext context) {
        OAuthAuthorization authorization = authorization(context);
        Instant issuedAt = issuedAt(context);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer().toString());
        claims.put("sub", subject(authorization));
        claims.put("aud", List.of(context.getRegisteredClient().getClientId()));
        claims.put("exp", issuedAt.plus(properties.idTokenTtl()));
        claims.put("iat", issuedAt);
        claims.put("auth_time", java.util.Date.from(authorization.authenticatedAt()));
        String nonce = authorization.attributes().authorizationRequest() == null
                ? null : authorization.attributes().authorizationRequest().nonce();
        if (nonce != null) claims.put("nonce", nonce);
        replaceClaims(context, claims);
    }

    private void customizeAccessToken(JwtEncodingContext context) {
        OAuthAuthorization authorization = authorization(context);
        JwtClaimsSet existing = context.getClaims().build();
        Instant issuedAt = issuedAt(context);
        String jti = existing.getId() == null ? UUID.randomUUID().toString() : existing.getId();
        String scopes = context.getAuthorizedScopes().stream().sorted().collect(Collectors.joining(" "));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer().toString());
        claims.put("sub", subject(authorization));
        claims.put("aud", List.of(properties.userInfoAudience()));
        claims.put("client_id", context.getRegisteredClient().getClientId());
        claims.put("scope", scopes);
        claims.put("jti", jti);
        claims.put("iat", issuedAt);
        claims.put("exp", issuedAt.plus(properties.accessTokenTtl()));
        replaceClaims(context, claims);
    }

    private OAuthAuthorization authorization(JwtEncodingContext context) {
        if (context.getAuthorization() == null) {
            throw new IllegalStateException("OAuth token authorization is required.");
        }
        return authorizations.findById(context.getAuthorization().getId())
                .orElseThrow(() -> new IllegalStateException("OAuth token authorization does not exist."));
    }

    private String subject(OAuthAuthorization authorization) {
        return subjects.getOrCreate(authorization.principalAccountId()).subject().toString();
    }

    private Instant issuedAt(JwtEncodingContext context) {
        Instant issuedAt = context.getClaims().build().getIssuedAt();
        if (issuedAt == null) throw new IllegalStateException("OAuth token issued-at is required.");
        return issuedAt;
    }

    private void replaceClaims(JwtEncodingContext context, Map<String, Object> allowlisted) {
        context.getClaims().claims(claims -> {
            claims.clear();
            claims.putAll(allowlisted);
        });
    }
}
