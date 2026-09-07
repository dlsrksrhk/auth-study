package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthUserInfoService;
import com.sweet.authstudy.oauth.application.OAuthUserInfoView;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * Adapts the typed application view to the OIDC protocol boundary.
 */
@Component
public final class OidcUserInfoMapper
        implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

    public static final String COMPANY_CLAIM = "https://auth-study.local/claims/company";
    public static final String ORGANIZATION_CLAIM = "https://auth-study.local/claims/organization";
    public static final String ROLES_CLAIM = "https://auth-study.local/claims/roles";

    private final OAuthUserInfoService service;

    public OidcUserInfoMapper(OAuthUserInfoService service) {
        this.service = service;
    }

    @Override
    public OidcUserInfo apply(OidcUserInfoAuthenticationContext context) {
        try {
            OAuth2Authorization authorization = required(context.getAuthorization());
            OAuth2AuthorizationRequest request = required(authorization.getAttribute(
                    OAuth2AuthorizationRequest.class.getName()));
            OAuth2Authorization.Token<OidcIdToken> idToken = required(
                    authorization.getToken(OidcIdToken.class));
            Map<String, Object> bearerClaims = bearerClaims(context.getAuthentication());
            OAuthUserInfoView view = service.userInfo(new OAuthUserInfoService.Request(
                    context.getAccessToken().getTokenValue(),
                    authorization.getId(),
                    authorization.getRegisteredClientId(),
                    authorization.getPrincipalName(),
                    request.getClientId(),
                    authorization.getAuthorizedScopes(),
                    context.getAccessToken().getScopes(),
                    text(bearerClaims.get("sub")),
                    text(idToken.getToken().getSubject()),
                    text(bearerClaims.get("client_id")),
                    scopes(bearerClaims.get("scope")),
                    audiences(bearerClaims.get("aud"))));
            return new OidcUserInfo(claims(view));
        } catch (RuntimeException exception) {
            throw invalidToken();
        }
    }

    private Map<String, Object> bearerClaims(Authentication authentication) {
        if (!(authentication instanceof OidcUserInfoAuthenticationToken userInfo)
                || !(userInfo.getPrincipal()
                instanceof AbstractOAuth2TokenAuthenticationToken<?> bearer)) {
            throw new IllegalArgumentException("Bearer authentication is required.");
        }
        return bearer.getTokenAttributes();
    }

    private Map<String, Object> claims(OAuthUserInfoView view) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", view.subject());
        view.profile().ifPresent(profile -> {
            claims.put("name", profile.name());
        });
        view.email().ifPresent(email -> {
            email.value().ifPresent(value -> claims.put("email", value));
            claims.put("email_verified", email.verified());
        });
        view.company().ifPresent(company -> claims.put(COMPANY_CLAIM,
                codeName(company.code(), company.name())));
        view.organization().ifPresent(organization -> claims.put(ORGANIZATION_CLAIM,
                organization(organization)));
        view.roles().ifPresent(roles -> claims.put(ROLES_CLAIM, roles));
        return claims;
    }

    private Map<String, Object> organization(OAuthUserInfoView.Organization organization) {
        Map<String, Object> value = new LinkedHashMap<>();
        organization.position().ifPresent(position -> value.put("position", codeName(position)));
        organization.primaryDepartment().ifPresent(primary ->
                value.put("primary_department", codeName(primary)));
        List<Map<String, Object>> secondary = organization.secondaryDepartments().stream()
                .map(this::codeName)
                .toList();
        value.put("secondary_departments", secondary);
        return value;
    }

    private Map<String, Object> codeName(OAuthUserInfoView.CodeName value) {
        return codeName(value.code(), value.name());
    }

    private Map<String, Object> codeName(String code, String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("name", name);
        return value;
    }

    private Set<String> scopes(Object value) {
        if (value instanceof String scopeValue && !scopeValue.isBlank()) {
            return exactSet(List.of(scopeValue.trim().split("\\s+")));
        }
        if (value instanceof Collection<?> collection) {
            return exactStrings(collection);
        }
        throw new IllegalArgumentException("Signed access-token scopes are invalid.");
    }

    private Set<String> audiences(Object value) {
        if (value instanceof String audience) return Set.of(text(audience));
        if (value instanceof Collection<?> collection) return exactStrings(collection);
        throw new IllegalArgumentException("Signed access-token audience is invalid.");
    }

    private Set<String> exactStrings(Collection<?> values) {
        LinkedHashSet<String> strings = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || !strings.add(text)) {
                throw new IllegalArgumentException("Signed access-token claim is invalid.");
            }
        }
        if (strings.isEmpty()) throw new IllegalArgumentException("Signed access-token claim is empty.");
        return Set.copyOf(strings);
    }

    private Set<String> exactSet(Collection<String> values) {
        return exactStrings(values);
    }

    private String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Required claim is invalid.");
        }
        return text;
    }

    private <T> T required(T value) {
        if (value == null) throw new IllegalArgumentException("Required protocol state is absent.");
        return value;
    }

    private OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
