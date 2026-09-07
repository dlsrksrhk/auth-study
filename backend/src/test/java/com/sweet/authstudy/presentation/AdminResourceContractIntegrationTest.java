package com.sweet.authstudy.presentation;

import com.jayway.jsonpath.JsonPath;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.presentation.CompanyAdminController;
import com.sweet.authstudy.hr.company.presentation.CompanyRequests.CreateCompanyRequest;
import com.sweet.authstudy.hr.company.presentation.CompanyRequests.UpdateCompanyRequest;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.presentation.UserAdminController;
import com.sweet.authstudy.identity.application.AccountService;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AdminResourceContractIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CompanyRepository companyRepository;
    @Autowired
    AccountService accountService;
    @Autowired
    CompanyService companyService;
    @Autowired
    PositionService positionService;
    @Autowired
    DepartmentService departmentService;
    @Autowired
    UserService userService;
    @Autowired
    JwtTokenService jwtTokenService;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    Clock clock;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private Account system;
    private AuthenticatedAccount systemActor;
    private String systemToken;

    @BeforeEach
    void setUp() {
        system = accountRepository.save(Account.createSystemAdmin(
                "resource-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        systemActor = new AuthenticatedAccount(system.id(), null, null, system.roles(), false);
        systemToken = jwtTokenService.issue(systemActor, false).accessToken();
    }

    @Test
    void position_department_user_membership_and_role_endpoints_follow_the_full_contract() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = ("R" + suffix).toUpperCase();
        String domain = suffix + ".resources.example";
        createCompany(companyCode, domain);
        mvc.perform(get("/api/v1/admin/companies")
                        .param("search", domain).param("status", "ACTIVE")
                        .param("sort", "name").param("page", "0").param("size", "1")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value(companyCode));

        String positionBody = mvc.perform(post("/api/v1/admin/companies/{companyCode}/positions", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"LEAD\",\"name\":\"Technical Lead\","
                                + "\"level\":60,\"displayOrder\":60}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/positions/LEAD")))
                .andReturn().getResponse().getContentAsString();
        Number positionVersion = JsonPath.read(positionBody, "$.version");
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/positions", companyCode)
                        .param("search", "technical").param("active", "true")
                        .param("sort", "name").param("page", "0").param("size", "1")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("LEAD"));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/positions/LEAD", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Lead II\",\"level\":70,\"displayOrder\":70,"
                                + "\"active\":true,\"version\":" + positionVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Lead II"));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/positions/LEAD", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Stale\",\"level\":70,\"displayOrder\":70,"
                                + "\"active\":true,\"version\":" + positionVersion + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        String departmentBody = mvc.perform(post("/api/v1/admin/companies/{companyCode}/departments", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"DEV\",\"name\":\"Development\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/departments/DEV")))
                .andReturn().getResponse().getContentAsString();
        Number departmentVersion = JsonPath.read(departmentBody, "$.version");
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/departments", companyCode)
                        .param("search", "develop").param("status", "ACTIVE").param("sort", "name")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/departments/DEV", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Platform Development\",\"status\":\"ACTIVE\","
                                + "\"version\":" + departmentVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Platform Development"));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/departments/DEV", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Stale\",\"status\":\"ACTIVE\","
                                + "\"version\":" + departmentVersion + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        String createdUser = mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"U001\",\"employeeNumber\":\"E-1001\","
                                + "\"name\":\"Kim\",\"loginEmail\":\"kim@" + domain + "\","
                                + "\"phone\":\"010-0000-0000\",\"hiredAt\":\"2026-08-20\","
                                + "\"workplace\":\"Seoul\",\"positionCode\":\"LEAD\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", endsWith("/users/U001")))
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn().getResponse().getContentAsString();
        String firstPassword = JsonPath.read(createdUser, "$.temporaryPassword");
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("U001"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        String updatedUser = mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/U001", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Kim Updated\",\"phone\":\"010-1111-1111\","
                                + "\"hiredAt\":\"2026-08-20\",\"workplace\":\"Busan\","
                                + "\"positionCode\":\"LEAD\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kim Updated"))
                .andReturn().getResponse().getContentAsString();
        Number userVersion = JsonPath.read(updatedUser, "$.version");
        String reset = mvc.perform(post(
                        "/api/v1/admin/companies/{companyCode}/users/U001/temporary-password", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reset, "$.temporaryPassword")).isNotEqualTo(firstPassword);
        String lockedUser = mvc.perform(put(
                        "/api/v1/admin/companies/{companyCode}/users/U001/status", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\",\"version\":" + userVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(lockedUser, "$.version").longValue())
                .isGreaterThan(userVersion.longValue());
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .param("search", "E-1001").param("status", "LOCKED")
                        .param("sort", "employeeNumber").param("page", "0").param("size", "1")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("U001"));

        String membership = mvc.perform(post(
                        "/api/v1/admin/companies/{companyCode}/users/U001/memberships", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"departmentCode\":\"DEV\",\"role\":\"MEMBER\","
                                + "\"primary\":true,\"startedAt\":\"2026-08-20T00:00:00Z\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/memberships/")))
                .andReturn().getResponse().getContentAsString();
        Number membershipId = JsonPath.read(membership, "$.id");
        Number membershipVersion = JsonPath.read(membership, "$.version");
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001/memberships", companyCode)
                        .param("search", "platform").param("status", "ACTIVE").param("sort", "startedAt")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        String promoted = mvc.perform(put(
                        "/api/v1/admin/companies/{companyCode}/users/U001/memberships/{id}",
                        companyCode, membershipId)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"HEAD\",\"primary\":true,\"version\":"
                                + membershipVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("HEAD"))
                .andReturn().getResponse().getContentAsString();
        Number promotedVersion = JsonPath.read(promoted, "$.version");
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/U001/memberships/{id}",
                        companyCode, membershipId)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\",\"primary\":false,\"version\":"
                                + membershipVersion + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
        mvc.perform(delete("/api/v1/admin/companies/{companyCode}/users/U001/memberships/{id}",
                        companyCode, membershipId).param("version", promotedVersion.toString())
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endedAt").isString());
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001/memberships", companyCode)
                        .param("search", "E-1001").param("status", "ENDED")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));

        long userId = userRepository.findByCompanyIdAndCode(
                companyRepository.findByCode(companyCode).orElseThrow().id(), "U001").orElseThrow().id();
        long accountId = accountRepository.findByUserId(userId).orElseThrow().id();
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/U001/admin-role", companyCode)
                .header(AUTHORIZATION, "Bearer " + systemToken)).andExpect(status().isNoContent());
        assertThat(accountRepository.findById(accountId).orElseThrow().roles()).contains(AccountRole.COMPANY_ADMIN);
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.hasItems("USER", "COMPANY_ADMIN")));
        mvc.perform(delete("/api/v1/admin/companies/{companyCode}/users/U001/admin-role", companyCode)
                .header(AUTHORIZATION, "Bearer " + systemToken)).andExpect(status().isNoContent());
        assertThat(accountRepository.findById(accountId).orElseThrow().roles()).doesNotContain(AccountRole.COMPANY_ADMIN);
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    void system_admin_cannot_change_its_own_system_role() {
        assertThatThrownBy(() -> accountService.assignCompanyAdmin(systemActor, system.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> accountService.revokeCompanyAdmin(systemActor, system.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void user_list_loads_account_roles_in_a_constant_number_of_statements() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String oneCode = ("O" + suffix).toUpperCase();
        String manyCode = ("M" + suffix).toUpperCase();
        createCompany(oneCode, "one-" + suffix + ".roles.example");
        createCompany(manyCode, "many-" + suffix + ".roles.example");
        createUser(oneCode, "U001", "E-ONE", "one@one-" + suffix + ".roles.example");
        for (int index = 1; index <= 4; index++) {
            createUser(manyCode, "U00" + index, "E-MANY-" + index,
                    "many" + index + "@many-" + suffix + ".roles.example");
        }
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/U002/admin-role", manyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isNoContent());

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", oneCode)
                        .param("page", "0").param("size", "20").param("sort", "code")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].roles[0]").value("USER"));
        long oneUserStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", manyCode)
                        .param("page", "0").param("size", "20").param("sort", "code")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[1].code").value("U002"))
                .andExpect(jsonPath("$.content[1].roles", org.hamcrest.Matchers.hasItems("USER", "COMPANY_ADMIN")));
        long manyUserStatements = statistics.getPrepareStatementCount();

        assertThat(manyUserStatements).isEqualTo(oneUserStatements);
    }

    @Test
    void unsafe_code_is_rejected_by_the_application_service_too() {
        assertThatThrownBy(() -> companyService.create(systemActor,
                new CreateCompanyCommand(" BAD/CODE ", "Bad", UUID.randomUUID() + ".example")))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(companyRepository.findByCode("BAD/CODE")).isEmpty();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String companyCode = "V" + suffix;
        String domain = suffix.toLowerCase() + ".validation.example";
        companyService.create(systemActor, new CreateCompanyCommand(companyCode, "Valid", domain));

        assertValidationFailure(() -> positionService.create(systemActor, companyCode,
                new CreatePositionCommand(" BAD?CODE ", "Bad", 1, 1)));
        assertValidationFailure(() -> departmentService.create(systemActor,
                new CreateDepartmentCommand(companyCode, " BAD#CODE ", "Bad", null)));
        assertValidationFailure(() -> userService.create(systemActor,
                new CreateUserCommand(companyCode, " BAD CODE ", "E-BAD", "Bad", "bad@" + domain,
                        "010-0000-0000", LocalDate.of(2026, 8, 20), "Seoul", null, "EMPLOYEE")));
    }

    @Test
    void system_only_controller_methods_are_annotated_for_method_security() throws Exception {
        PreAuthorize companyCreate = CompanyAdminController.class
                .getMethod("create", CreateCompanyRequest.class).getAnnotation(PreAuthorize.class);
        PreAuthorize companyUpdate = CompanyAdminController.class
                .getMethod("update", String.class, UpdateCompanyRequest.class).getAnnotation(PreAuthorize.class);
        PreAuthorize roleGrant = UserAdminController.class
                .getMethod("assignAdminRole", String.class, String.class).getAnnotation(PreAuthorize.class);
        PreAuthorize roleRevoke = UserAdminController.class
                .getMethod("revokeAdminRole", String.class, String.class).getAnnotation(PreAuthorize.class);
        assertThat(companyCreate.value()).isEqualTo("hasRole('SYSTEM_ADMIN')");
        assertThat(companyUpdate.value()).isEqualTo("hasRole('SYSTEM_ADMIN')");
        assertThat(roleGrant.value()).isEqualTo("hasRole('SYSTEM_ADMIN')");
        assertThat(roleRevoke.value()).isEqualTo("hasRole('SYSTEM_ADMIN')");
    }

    @Test
    void every_admin_list_rejects_non_whitelisted_sort() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String companyCode = "S" + suffix;
        String domain = suffix.toLowerCase() + ".sort.example";
        createCompany(companyCode, domain);
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"U001\",\"employeeNumber\":\"E-1\","
                                + "\"name\":\"Sort User\",\"loginEmail\":\"sort@" + domain + "\","
                                + "\"phone\":\"010-0000-0000\",\"hiredAt\":\"2026-08-20\","
                                + "\"workplace\":\"Seoul\",\"positionCode\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());

        var lists = java.util.List.of(
                get("/api/v1/admin/companies"),
                get("/api/v1/admin/companies/{companyCode}/positions", companyCode),
                get("/api/v1/admin/companies/{companyCode}/departments", companyCode),
                get("/api/v1/admin/companies/{companyCode}/users", companyCode),
                get("/api/v1/admin/companies/{companyCode}/users/U001/memberships", companyCode));
        for (var request : lists) {
            mvc.perform(request.param("sort", "passwordHash")
                            .header(AUTHORIZATION, "Bearer " + systemToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    private void assertValidationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private void createCompany(String code, String domain) throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Resources\","
                                + "\"emailDomain\":\"" + domain + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createUser(String companyCode, String code, String employeeNumber, String email) throws Exception {
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"employeeNumber\":\"" + employeeNumber + "\","
                                + "\"name\":\"Role User\",\"loginEmail\":\"" + email + "\","
                                + "\"phone\":\"010-0000-0000\",\"hiredAt\":\"2026-08-20\","
                                + "\"workplace\":\"Seoul\",\"positionCode\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
    }
}
