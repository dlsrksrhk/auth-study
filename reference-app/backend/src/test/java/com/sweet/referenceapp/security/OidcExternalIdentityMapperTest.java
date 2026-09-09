package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;

class OidcExternalIdentityMapperTest {

    @Test
    void mapsRequiredUserInfoClaimsAndDefaultsOptionalClaims() {
        var mapper = new OidcExternalIdentityMapper();

        var profile = mapper.map(token("one"), new OidcUserInfo(Map.of("sub", "one")));

        assertThat(profile.subject()).isEqualTo("one");
        assertThat(profile.email()).isNull();
        assertThat(profile.hrRoles()).isEmpty();
    }

    @Test
    void rejectsUserInfoSubjectThatDoesNotMatchIdToken() {
        var mapper = new OidcExternalIdentityMapper();

        assertThatThrownBy(() -> mapper.map(token("one"), new OidcUserInfo(Map.of("sub", "two"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsOnlyAllowedUserInfoClaimsAndPreservesIdentityRepresentation() {
        var mapper = new OidcExternalIdentityMapper();
        var issuer = "https://IDP.Example:443/tenant";
        var subject = " User-A ";
        var token = token(issuer, subject, Map.of("email", "token@example.test", "name", "Token Name"));
        var company = Map.<String, Object>of("code", "ACME", "name", "Acme Corp");
        var organization = Map.<String, Object>of(
                "position", Map.of("code", "ENG", "name", "Engineer"),
                "primary_department", Map.of("code", "PLATFORM", "name", "Platform"),
                "secondary_departments", List.of(Map.of("code", "SEC", "name", "Security")));
        var info = new OidcUserInfo(Map.of(
                "sub", subject,
                "email", "userinfo@example.test",
                "name", "UserInfo Name",
                "https://auth-study.local/claims/company", company,
                "https://auth-study.local/claims/organization", organization,
                "https://auth-study.local/claims/roles", List.of("HR_ADMIN", "EMPLOYEE", "HR_ADMIN"),
                "ignored", "not persisted"));

        var profile = mapper.map(token, info);

        assertThat(profile.issuer()).isEqualTo(URI.create(issuer));
        assertThat(profile.subject()).isEqualTo(subject);
        assertThat(profile.email()).isEqualTo("userinfo@example.test");
        assertThat(profile.displayName()).isEqualTo("UserInfo Name");
        assertThat(profile.company()).isEqualTo(company);
        assertThat(profile.organization()).isEqualTo(organization);
        assertThat(profile.hrRoles()).isEqualTo(Set.of("HR_ADMIN", "EMPLOYEE"));
    }

    @Test
    void doesNotBackfillOptionalClaimsFromIdToken() {
        var mapper = new OidcExternalIdentityMapper();
        var token = token("http://idp.localhost:8080", "one",
                Map.of("email", "token@example.test", "name", "Token Name"));

        var profile = mapper.map(token, new OidcUserInfo(Map.of("sub", "one")));

        assertThat(profile.email()).isNull();
        assertThat(profile.displayName()).isNull();
        assertThat(profile.company()).isNull();
        assertThat(profile.organization()).isNull();
        assertThat(profile.hrRoles()).isEmpty();
    }

    @Test
    void acceptsExplicitNullOptionalClaims() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "one");
        claims.put("email", null);
        claims.put("name", null);
        claims.put("https://auth-study.local/claims/company", null);
        claims.put("https://auth-study.local/claims/organization", null);
        claims.put("https://auth-study.local/claims/roles", null);

        var profile = new OidcExternalIdentityMapper().map(token("one"), new OidcUserInfo(claims));

        assertThat(profile.email()).isNull();
        assertThat(profile.displayName()).isNull();
        assertThat(profile.company()).isNull();
        assertThat(profile.organization()).isNull();
        assertThat(profile.hrRoles()).isEmpty();
    }

    @Test
    void rejectsMissingIdentityInformation() {
        var mapper = new OidcExternalIdentityMapper();

        assertThatThrownBy(() -> mapper.map(token("one"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identity information missing");
        assertThatThrownBy(() -> mapper.map(null, new OidcUserInfo(Map.of("sub", "one"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identity information missing");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClaims")
    void rejectsInvalidAllowedUserInfoClaims(String description, Map<String, Object> claims) {
        var mapper = new OidcExternalIdentityMapper();

        assertThatThrownBy(() -> mapper.map(token("one"), new OidcUserInfo(claims)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidClaims() {
        var nullRole = new ArrayList<Object>();
        nullRole.add(null);
        return Stream.of(
                Arguments.of("missing subject", Map.of()),
                Arguments.of("numeric subject", Map.of("sub", 1)),
                Arguments.of("blank subject", Map.of("sub", "  ")),
                Arguments.of("numeric name", Map.of("sub", "one", "name", 1)),
                Arguments.of("numeric email", Map.of("sub", "one", "email", 1)),
                Arguments.of("string company", claims("https://auth-study.local/claims/company", "ACME")),
                Arguments.of("non-string company key", claims(
                        "https://auth-study.local/claims/company", Map.of(1, "ACME"))),
                Arguments.of("unsupported company field", claims(
                        "https://auth-study.local/claims/company",
                        Map.of("code", "ACME", "name", "Acme", "secret", "value"))),
                Arguments.of("invalid organization nested value", claims(
                        "https://auth-study.local/claims/organization",
                        Map.of("position", "Engineer"))),
                Arguments.of("non-string organization key", claims(
                        "https://auth-study.local/claims/organization", Map.of(1, "value"))),
                Arguments.of("string roles", claims("https://auth-study.local/claims/roles", "HR_ADMIN")),
                Arguments.of("numeric role", claims(
                        "https://auth-study.local/claims/roles", List.of("HR_ADMIN", 1))),
                Arguments.of("null role", claims("https://auth-study.local/claims/roles", nullRole)),
                Arguments.of("blank role", claims(
                        "https://auth-study.local/claims/roles", List.of("HR_ADMIN", " "))));
    }

    private static Map<String, Object> claims(String key, Object value) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "one");
        claims.put(key, value);
        return claims;
    }

    private static OidcIdToken token(String sub) {
        return token("http://idp.localhost:8080", sub, Map.of());
    }

    private static OidcIdToken token(String issuer, String sub, Map<String, Object> additionalClaims) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer);
        claims.put("sub", sub);
        claims.putAll(additionalClaims);
        return new OidcIdToken(
                "fixture-id-token",
                Instant.parse("2026-09-09T00:00:00Z"),
                Instant.parse("2026-09-09T01:00:00Z"),
                claims);
    }
}
