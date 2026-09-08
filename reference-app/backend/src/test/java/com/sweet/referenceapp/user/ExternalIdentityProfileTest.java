package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalIdentityProfileTest {

    @Test
    void rejectsMissingAndMalformedIdentity() {
        assertThatThrownBy(() -> profile(null, "subject")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profile(URI.create("/relative"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("ftp://idp.example"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("https:///issuer"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("https://idp.example?tenant=a"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("https://idp.example#issuer"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("https://idp.example"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesIdentityLengthWithoutNormalizingIdentity() {
        var issuer = URI.create("https://idp.example/" + "a".repeat(1004));
        assertThat(issuer.toString()).hasSize(1024);
        var profile = profile(issuer, " subject ");

        assertThat(profile.issuer().toString()).isEqualTo(issuer.toString());
        assertThat(profile.subject()).isEqualTo(" subject ");
        assertThatThrownBy(() -> profile(URI.create(issuer + "a"), "subject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(URI.create("https://idp.example"), "a".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInternalIdAndWrongClaimShape() {
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null,
                Map.of("code", "DEMO", "name", "Demo", "id", 42), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null, null,
                Map.of("secondary_departments", "not-a-list"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesEveryPublicClaimShape() {
        assertThatThrownBy(() -> snapshot(Map.of("code", "DEMO"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Map.of("code", "DEMO", "name", " "), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(null, Map.of("unknown", "value"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(null, Map.of("position", "developer"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(null,
                Map.of("secondary_departments", List.of(Map.of("code", 1, "name", "Engineering"))),
                Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(null, null, new java.util.HashSet<>(java.util.Arrays.asList("HR", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(null, null, Set.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesNestedClaims() {
        var department = new HashMap<String, Object>(Map.of("code", "ENG", "name", "Engineering"));
        var departments = new ArrayList<Map<String, Object>>();
        departments.add(department);
        var snapshot = new ExternalUserSnapshot(null, null, null,
                Map.of("secondary_departments", departments), Set.of());

        department.put("name", "Changed");
        departments.clear();

        assertThat(snapshot.organization().get("secondary_departments"))
                .isEqualTo(List.of(Map.of("code", "ENG", "name", "Engineering")));
        assertThatThrownBy(() -> snapshot.organization().put("position", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void profileNormalizesOnlyAbsentRolesAndSnapshotsCopiedClaims() {
        var company = new HashMap<String, Object>(Map.of("code", "DEMO", "name", "Demo"));
        var profile = new ExternalIdentityProfile(URI.create("http://idp.localhost:8080"), "opaque-1",
                null, "Display", company, null, null);
        company.put("name", "Changed");

        assertThat(profile.hrRoles()).isEmpty();
        assertThat(profile.company()).isEqualTo(Map.of("code", "DEMO", "name", "Demo"));
        assertThat(profile.snapshot()).isEqualTo(new ExternalUserSnapshot(
                null, "Display", Map.of("code", "DEMO", "name", "Demo"), null, Set.of()));
    }

    @Test
    void omittedDepartmentsBecomeAnEmptyListAndNullStructuresRemainNull() {
        var organization = Map.<String, Object>of(
                "position", Map.of("code", "DEV", "name", "Developer"));

        assertThat(snapshot(null, organization, Set.of()).organization())
                .isEqualTo(Map.of(
                        "position", Map.of("code", "DEV", "name", "Developer"),
                        "secondary_departments", List.of()));
        assertThat(snapshot(null, null, null).company()).isNull();
        assertThat(snapshot(null, null, null).organization()).isNull();
        assertThat(snapshot(null, null, null).hrRoles()).isEmpty();
    }

    private static ExternalIdentityProfile profile(URI issuer, String subject) {
        return new ExternalIdentityProfile(issuer, subject, null, null, null, null, null);
    }

    private static ExternalUserSnapshot snapshot(Map<String, Object> company,
            Map<String, Object> organization, Set<String> roles) {
        return new ExternalUserSnapshot(null, null, company, organization, roles);
    }
}
