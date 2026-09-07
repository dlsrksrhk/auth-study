package com.sweet.authstudy.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles({"test", "dev"})
class HrAdminAcceptanceTest {

    private static final String BROWSER_ORIGIN = "http://localhost:3000";
    private static final String REFRESH_COOKIE = "AUTH_STUDY_REFRESH";
    private static final String SYSTEM_EMAIL = "admin@auth-study.local";
    private static final String SYSTEM_PASSWORD = "AuthStudy1234!";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void completes_the_hr_admin_journey_through_http_jwt_cookie_and_origin_boundaries() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String acmeCode = "A" + suffix.toUpperCase();
        String betaCode = "B" + suffix.toUpperCase();
        String acmeDomain = "acme-" + suffix + ".acceptance.example";
        String betaDomain = "beta-" + suffix + ".acceptance.example";
        String companyAdminEmail = "admin@" + acmeDomain;
        String userEmail = "user@" + acmeDomain;
        String changedAdminPassword = "ChangedAdmin1234!";
        String changedUserPassword = "ChangedUser1234!";
        String unsafeName = "<img src=x onerror=alert(1)>";

        // 1. The dev seeder account is consumed through the real login contract.
        Login systemLogin = login(SYSTEM_EMAIL, SYSTEM_PASSWORD, false);
        String systemToken = systemLogin.accessToken();
        assertThat(systemLogin.refreshCookie()).isNotNull();

        // 2. A company is provisioned with its five default positions.
        JsonNode acme = createCompany(systemToken, acmeCode, "ACME " + suffix, acmeDomain);
        assertThat(acme.path("status").asText()).isEqualTo("ACTIVE");
        JsonNode positions = responseJson(mvc.perform(get(
                        "/api/v1/admin/companies/{companyCode}/positions", acmeCode)
                        .header(AUTHORIZATION, bearer(systemToken))
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andReturn());
        assertThat(textValues(positions.path("content"), "code")).containsExactlyInAnyOrder(
                "EMPLOYEE", "ASSISTANT_MANAGER", "MANAGER",
                "DEPUTY_GENERAL_MANAGER", "GENERAL_MANAGER");

        // 3. A company administrator starts as a pending user with a one-time password.
        JsonNode createdAdmin = createUser(systemToken, acmeCode, "ADMIN01", "E-ADMIN-" + suffix,
                "ACME Admin", companyAdminEmail);
        String adminTemporaryPassword = createdAdmin.path("temporaryPassword").asText();
        assertThat(adminTemporaryPassword).isNotBlank();
        assertThat(createdAdmin.path("user").path("status").asText()).isEqualTo("PENDING");
        long adminVersion = createdAdmin.path("user").path("version").asLong();

        // 4. The system administrator creates HQ, assigns the primary HEAD, then activates the user.
        JsonNode hq = createDepartment(systemToken, acmeCode, "HQ", "Headquarters", null);
        JsonNode adminMembership = assignMembership(systemToken, acmeCode, "ADMIN01", "HQ", "HEAD", true);
        assertThat(adminMembership.path("primary").asBoolean()).isTrue();
        assertThat(adminMembership.path("role").asText()).isEqualTo("HEAD");
        JsonNode activeAdmin = changeStatus(systemToken, acmeCode, "ADMIN01", "ACTIVE", adminVersion);
        assertThat(activeAdmin.path("status").asText()).isEqualTo("ACTIVE");

        // A temporary-password login is deliberately restricted to password change and has no refresh token.
        Login temporaryAdminLogin = login(companyAdminEmail, adminTemporaryPassword, true);
        assertThat(temporaryAdminLogin.refreshCookie()).isNull();
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/positions", acmeCode)
                        .header(AUTHORIZATION, bearer(temporaryAdminLogin.accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 5. Grant the administrator role through the system-admin HTTP endpoint.
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/ADMIN01/admin-role", acmeCode)
                        .header(AUTHORIZATION, bearer(systemToken)))
                .andExpect(status().isNoContent());

        // 6. The password-change-only token changes the first password; a later role change revokes refresh.
        changePassword(temporaryAdminLogin.accessToken(), adminTemporaryPassword, changedAdminPassword);
        Login preRoleChangeLogin = login(companyAdminEmail, changedAdminPassword, false);
        assertThat(preRoleChangeLogin.refreshCookie()).isNotNull();
        mvc.perform(delete("/api/v1/admin/companies/{companyCode}/users/ADMIN01/admin-role", acmeCode)
                        .header(AUTHORIZATION, bearer(systemToken)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh")
                        .header(ORIGIN, BROWSER_ORIGIN)
                        .cookie(preRoleChangeLogin.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        Login preGrantLogin = login(companyAdminEmail, changedAdminPassword, false);
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/ADMIN01/admin-role", acmeCode)
                        .header(AUTHORIZATION, bearer(systemToken)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh")
                        .header(ORIGIN, BROWSER_ORIGIN)
                        .cookie(preGrantLogin.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        Login companyAdminLogin = login(companyAdminEmail, changedAdminPassword, false);
        String companyAdminToken = companyAdminLogin.accessToken();
        mvc.perform(get("/api/v1/auth/me").header(AUTHORIZATION, bearer(companyAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[?(@ == 'COMPANY_ADMIN')]").exists())
                .andExpect(jsonPath("$.companyCode").value(acmeCode));

        // 7. The company administrator builds the nested HQ -> DEV -> API tree.
        JsonNode dev = createDepartment(companyAdminToken, acmeCode, "DEV", "Development", "HQ");
        JsonNode api = createDepartment(companyAdminToken, acmeCode, "API", "API Platform", "DEV");
        assertThat(dev.path("parentDepartmentId").asLong()).isEqualTo(hq.path("id").asLong());
        assertThat(api.path("parentDepartmentId").asLong()).isEqualTo(dev.path("id").asLong());

        // 8. A user has primary and secondary memberships before activation.
        MvcResult unsafeCreateResult = createUserResult(companyAdminToken, acmeCode, "USER01",
                "E-USER-" + suffix, unsafeName, userEmail);
        String unsafeCreateJson = unsafeCreateResult.getResponse().getContentAsString();
        JsonNode createdUser = objectMapper.readTree(unsafeCreateJson);
        String encodedUnsafeName = objectMapper.writeValueAsString(unsafeName);
        assertThat(unsafeCreateJson).contains("\"name\":" + encodedUnsafeName);
        assertThat(createdUser.path("user").path("name").asText()).isEqualTo(unsafeName);
        assertThat(fieldNames(createdUser.path("user"))).containsExactlyInAnyOrder(
                "id", "companyId", "code", "employeeNumber", "name", "loginEmail", "roles", "phone",
                "hiredAt", "workplace", "profileImageUrl", "positionId", "status", "version",
                "createdAt", "updatedAt");
        assertThat(createdUser.path("user").path("roles").size()).isEqualTo(1);
        assertThat(createdUser.path("user").path("roles").get(0).asText()).isEqualTo("USER");
        assertThat(createdUser.path("user").path("status").asText()).isEqualTo("PENDING");
        String userTemporaryPassword = createdUser.path("temporaryPassword").asText();
        long userVersion = createdUser.path("user").path("version").asLong();
        JsonNode primaryMembership = assignMembership(
                companyAdminToken, acmeCode, "USER01", "DEV", "MEMBER", true);
        JsonNode secondaryMembership = assignMembership(
                companyAdminToken, acmeCode, "USER01", "API", "MEMBER", false);
        assertThat(primaryMembership.path("primary").asBoolean()).isTrue();
        assertThat(secondaryMembership.path("primary").asBoolean()).isFalse();
        JsonNode activeUser = changeStatus(companyAdminToken, acmeCode, "USER01", "ACTIVE", userVersion);
        assertThat(activeUser.path("status").asText()).isEqualTo("ACTIVE");

        // 9. Tenant isolation denies an ACME administrator access to BETA.
        createCompany(systemToken, betaCode, "BETA " + suffix, betaDomain);
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/positions", betaCode)
                        .header(AUTHORIZATION, bearer(companyAdminToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 10. LOCKED prevents both a new login and rotation of an already-issued refresh cookie.
        Login temporaryUserLogin = login(userEmail, userTemporaryPassword, true);
        changePassword(temporaryUserLogin.accessToken(), userTemporaryPassword, changedUserPassword);
        Login activeUserLogin = login(userEmail, changedUserPassword, false);
        assertThat(activeUserLogin.refreshCookie()).isNotNull();
        JsonNode lockedUser = changeStatus(companyAdminToken, acmeCode, "USER01", "LOCKED",
                activeUser.path("version").asLong());
        assertThat(lockedUser.path("status").asText()).isEqualTo("LOCKED");
        loginRejected(userEmail, changedUserPassword);
        mvc.perform(post("/api/v1/auth/refresh")
                        .header(ORIGIN, BROWSER_ORIGIN)
                        .cookie(activeUserLogin.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        // 11. Every mutation category is visible in audit, with no credential material retained.
        MvcResult auditResult = mvc.perform(get(
                        "/api/v1/admin/companies/{companyCode}/audit-logs", acmeCode)
                        .header(AUTHORIZATION, bearer(companyAdminToken))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String auditJson = auditResult.getResponse().getContentAsString();
        JsonNode audit = objectMapper.readTree(auditJson);
        List<String> auditActions = orderedTextValues(audit.path("content"), "action");
        assertThat(auditActions).contains(
                "COMPANY_CREATE", "USER_CREATE", "DEPARTMENT_CREATE", "MEMBERSHIP_CREATE",
                "USER_STATUS_CHANGE", "COMPANY_ADMIN_GRANT", "COMPANY_ADMIN_REVOKE");
        assertThat(auditActions).filteredOn("COMPANY_CREATE"::equals).hasSize(1);
        assertThat(auditActions).filteredOn("USER_CREATE"::equals).hasSize(2);
        assertThat(auditActions).filteredOn("DEPARTMENT_CREATE"::equals).hasSize(3);
        assertThat(auditActions).filteredOn("MEMBERSHIP_CREATE"::equals).hasSize(3);
        assertThat(auditActions).filteredOn("USER_STATUS_CHANGE"::equals).hasSize(3);
        assertThat(auditActions).filteredOn("COMPANY_ADMIN_GRANT"::equals).hasSize(2);
        assertThat(auditActions).filteredOn("COMPANY_ADMIN_REVOKE"::equals).hasSize(1);
        assertThat(auditJson).doesNotContain(
                SYSTEM_PASSWORD, adminTemporaryPassword, userTemporaryPassword,
                changedAdminPassword, changedUserPassword,
                systemLogin.refreshCookie().getValue(), preRoleChangeLogin.refreshCookie().getValue(),
                preGrantLogin.refreshCookie().getValue(), companyAdminLogin.refreshCookie().getValue(),
                activeUserLogin.refreshCookie().getValue(), systemToken,
                temporaryAdminLogin.accessToken(), preRoleChangeLogin.accessToken(),
                preGrantLogin.accessToken(), companyAdminToken,
                temporaryUserLogin.accessToken(), activeUserLogin.accessToken());
        for (JsonNode entry : audit.path("content")) {
            assertNoSecretFields(entry.path("details"));
        }
    }

    private JsonNode createCompany(String token, String code, String name, String domain) throws Exception {
        return responseJson(mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("code", code, "name", name, "emailDomain", domain))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/companies/" + code)))
                .andReturn());
    }

    private JsonNode createDepartment(
            String token, String companyCode, String code, String name, String parentCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        if (parentCode != null) body.put("parentCode", parentCode);
        return responseJson(mvc.perform(post(
                        "/api/v1/admin/companies/{companyCode}/departments", companyCode)
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private JsonNode createUser(
            String token, String companyCode, String code, String employeeNumber,
            String name, String email) throws Exception {
        return responseJson(createUserResult(token, companyCode, code, employeeNumber, name, email));
    }

    private MvcResult createUserResult(
            String token, String companyCode, String code, String employeeNumber,
            String name, String email) throws Exception {
        return mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "code", code,
                                "employeeNumber", employeeNumber,
                                "name", name,
                                "loginEmail", email,
                                "phone", "010-0000-0000",
                                "hiredAt", "2026-08-20",
                                "workplace", "Seoul",
                                "positionCode", "EMPLOYEE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();
    }

    private JsonNode assignMembership(
            String token, String companyCode, String userCode, String departmentCode,
            String role, boolean primary) throws Exception {
        return responseJson(mvc.perform(post(
                        "/api/v1/admin/companies/{companyCode}/users/{userCode}/memberships",
                        companyCode, userCode)
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "departmentCode", departmentCode,
                                "role", role,
                                "primary", primary,
                                "startedAt", Instant.now().toString()))))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private JsonNode changeStatus(
            String token, String companyCode, String userCode, String statusValue, long version) throws Exception {
        return responseJson(mvc.perform(put(
                        "/api/v1/admin/companies/{companyCode}/users/{userCode}/status",
                        companyCode, userCode)
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("status", statusValue, "version", version))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Login login(String email, String password, boolean mustChangePassword) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.mustChangePassword").value(mustChangePassword))
                .andReturn();
        JsonNode response = responseJson(result);
        return new Login(response.path("accessToken").asText(), refreshCookie(result));
    }

    private void loginRejected(String email, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private void changePassword(String token, String currentPassword, String newPassword) throws Exception {
        mvc.perform(post("/api/v1/auth/password")
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", currentPassword,
                                "newPassword", newPassword))))
                .andExpect(status().isNoContent());
    }

    private Cookie refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader(SET_COOKIE);
        if (header == null) return null;
        String prefix = REFRESH_COOKIE + "=";
        int start = header.indexOf(prefix);
        if (start < 0) return null;
        int valueStart = start + prefix.length();
        int end = header.indexOf(';', valueStart);
        return new Cookie(REFRESH_COOKIE, header.substring(valueStart, end < 0 ? header.length() : end));
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Set<String> textValues(JsonNode array, String field) {
        Set<String> values = new HashSet<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private List<String> orderedTextValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertNoSecretFields(JsonNode node) {
        if (!node.isObject()) return;
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String normalized = field.getKey().toLowerCase();
            assertThat(normalized).doesNotContain("password", "token", "secret");
            assertNoSecretFields(field.getValue());
        }
    }

    private record Login(String accessToken, Cookie refreshCookie) {
    }
}
