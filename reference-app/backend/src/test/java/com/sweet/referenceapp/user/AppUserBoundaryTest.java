package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppUserBoundaryTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializedViewContainsOnlyTheAllowlistedExternalSnapshot() throws Exception {
        var snapshot = new ExternalUserSnapshot("user@example.test", "User",
                Map.of("code", "DEMO", "name", "Demo"),
                Map.of("position", Map.of("code", "DEV", "name", "Developer"),
                        "primary_department", Map.of("code", "ENG", "name", "Engineering"),
                        "secondary_departments", List.of(Map.of("code", "QA", "name", "Quality"))),
                Set.of("COMPANY_ADMIN"));
        var json = objectMapper.writeValueAsString(AppUserView.from(AppUser.create(UUID.randomUUID(),
                "https://issuer.example", "subject", snapshot, Instant.EPOCH)));
        assertThat(json).contains("user@example.test", "COMPANY_ADMIN", "secondary_departments")
                .doesNotContain("access_token", "refresh_token", "company_id", "user_id", "account_id");
    }

    @Test
    void companyRejectsTokenOrInternalIdentifierKeys() {
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null,
                Map.of("code", "DEMO", "name", "Demo", "access_token", "synthetic"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null,
                Map.of("code", "DEMO", "name", "Demo", "company_id", 7), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedDepartmentRejectsTokenOrInternalIdentifierKeys() {
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null, null,
                Map.of("secondary_departments", List.of(Map.of("code", "QA", "name", "Quality",
                        "refresh_token", "synthetic"))), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalUserSnapshot(null, null, null,
                Map.of("primary_department", Map.of("code", "ENG", "name", "Engineering", "department_id", 9)), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
