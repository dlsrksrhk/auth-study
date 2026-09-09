package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.stereotype.Component;

@Component
public class OidcExternalIdentityMapper {

    private static final String COMPANY_CLAIM = "https://auth-study.local/claims/company";
    private static final String ORGANIZATION_CLAIM = "https://auth-study.local/claims/organization";
    private static final String ROLES_CLAIM = "https://auth-study.local/claims/roles";

    public ExternalIdentityProfile map(OidcIdToken token, OidcUserInfo info) {
        if (token == null || info == null) {
            throw new IllegalArgumentException("Identity information missing");
        }
        var claims = info.getClaims();
        validateClaims(claims);
        if (!Objects.equals(token.getSubject(), claims.get("sub"))) {
            throw new IllegalArgumentException("Identity mismatch");
        }
        var snapshot = snapshot(claims);
        return new ExternalIdentityProfile(
                URI.create(token.getClaimAsString("iss")),
                token.getSubject(),
                snapshot.email(),
                snapshot.displayName(),
                snapshot.company(),
                snapshot.organization(),
                snapshot.hrRoles());
    }

    public void validateClaims(Map<String, Object> claims) {
        if (claims == null || !(claims.get("sub") instanceof String subject) || subject.isBlank()) {
            throw new IllegalArgumentException("UserInfo subject is invalid");
        }
        requireNullableString(claims, "name");
        requireNullableString(claims, "email");
        snapshot(claims);
    }

    private ExternalUserSnapshot snapshot(Map<String, Object> claims) {
        return new ExternalUserSnapshot(
                nullableString(claims.get("email")),
                nullableString(claims.get("name")),
                nullableStringMap(claims.get(COMPANY_CLAIM)),
                nullableStringMap(claims.get(ORGANIZATION_CLAIM)),
                roles(claims.get(ROLES_CLAIM)));
    }

    private static void requireNullableString(Map<String, Object> claims, String name) {
        var value = claims.get(name);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("UserInfo claim has invalid type");
        }
    }

    private static String nullableString(Object value) {
        return value == null ? null : (String) value;
    }

    private static Map<String, Object> nullableStringMap(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("UserInfo claim has invalid type");
        }
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("UserInfo claim has invalid type");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static Set<String> roles(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("UserInfo claim has invalid type");
        }
        var roles = new LinkedHashSet<String>();
        for (var role : list) {
            if (!(role instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("UserInfo role is invalid");
            }
            roles.add(text);
        }
        return roles;
    }
}
