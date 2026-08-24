package com.sweet.authstudy.presentation;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AdminApiContractIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoSpyBean AccountRepository accountRepository;
    @MockitoSpyBean CompanyRepository companyRepository;
    @MockitoSpyBean PositionRepository positionRepository;
    @MockitoSpyBean DepartmentRepository departmentRepository;
    @MockitoSpyBean MembershipRepository membershipRepository;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    private String systemToken;

    @BeforeEach
    void setUp() {
        Account system = accountRepository.save(Account.createSystemAdmin(
                "system-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        systemToken = jwtTokenService.issue(new AuthenticatedAccount(
                system.id(), null, null, system.roles(), false), false).accessToken();
    }

    @Test
    void company_create_returns_201_and_location_then_list_has_page_metadata() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "C" + suffix;
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Acme\","
                                + "\"emailDomain\":\"" + suffix + ".example\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/admin/companies/" + code.toUpperCase())));

        mvc.perform(get("/api/v1/admin/companies?page=0&size=20&sort=code")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    void invalid_company_request_returns_validation_problem() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"\",\"emailDomain\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformed_enum_is_also_reported_as_a_validation_problem() throws Exception {
        mvc.perform(put("/api/v1/admin/companies/UNKNOWN")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"status\":\"BROKEN\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void company_update_absent_duplicate_and_stale_versions_follow_the_contract() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "C" + suffix;
        String create = "{\"code\":\"" + code + "\",\"name\":\"Acme\","
                + "\"emailDomain\":\"" + suffix + ".example\"}";
        String createdBody = mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON).content(create))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number version = com.jayway.jsonpath.JsonPath.read(createdBody, "$.version");

        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON).content(create))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        mvc.perform(get("/api/v1/admin/companies/DOES_NOT_EXIST")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}", code)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Acme Korea\",\"status\":\"ACTIVE\",\"version\":"
                                + version.longValue() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Korea"));
        mvc.perform(put("/api/v1/admin/companies/{companyCode}", code)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Stale\",\"status\":\"ACTIVE\",\"version\":"
                                + version.longValue() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    void user_search_treats_sql_shaped_input_as_data_and_never_exposes_saved_passwords() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "C" + suffix;
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Acme\","
                                + "\"emailDomain\":\"" + suffix + ".example\"}"))
                .andExpect(status().isCreated());
        String userBody = "{\"code\":\"U001\",\"employeeNumber\":\"E-1001\","
                + "\"name\":\"Kim\",\"loginEmail\":\"kim@" + suffix + ".example\","
                + "\"phone\":\"010-0000-0000\",\"hiredAt\":\"2026-08-20\","
                + "\"workplace\":\"Seoul\",\"positionCode\":\"EMPLOYEE\"}";
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", code)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON).content(userBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").isString());

        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", code)
                        .param("search", "'%27 OR 1=1 --")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$..temporaryPassword").doesNotExist());
    }

    @Test
    void excessively_large_page_is_rejected_consistently() throws Exception {
        mvc.perform(get("/api/v1/admin/companies")
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void all_admin_lists_reject_an_offset_that_cannot_be_safely_queried() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = "P" + suffix;
        createCompany(companyCode, suffix + ".page.example");
        createUser(companyCode, suffix + ".page.example", "U001");

        var paths = java.util.List.of(
                "/api/v1/admin/companies",
                "/api/v1/admin/companies/" + companyCode + "/positions",
                "/api/v1/admin/companies/" + companyCode + "/departments",
                "/api/v1/admin/companies/" + companyCode + "/users",
                "/api/v1/admin/companies/" + companyCode + "/users/U001/memberships");
        for (String path : paths) {
            mvc.perform(get(path).param("page", String.valueOf(Integer.MAX_VALUE))
                            .header(AUTHORIZATION, "Bearer " + systemToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void unsafe_business_codes_are_rejected_without_persistence() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = "S" + suffix;
        createCompany(companyCode, suffix + ".safe.example");

        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" BAD/CODE \",\"name\":\"Bad\","
                                + "\"emailDomain\":\"bad-" + suffix + ".example\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/positions", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" BAD?CODE \",\"name\":\"Bad\",\"level\":1,\"displayOrder\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/departments", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" BAD#CODE \",\"name\":\"Bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" BAD CODE \",\"employeeNumber\":\"E-BAD\","
                                + "\"name\":\"Bad\",\"loginEmail\":\"bad@" + suffix + ".safe.example\","
                                + "\"phone\":\"010-0000-0000\",\"hiredAt\":\"2026-08-20\","
                                + "\"workplace\":\"Seoul\",\"positionCode\":\"EMPLOYEE\"}"))
                .andExpect(status().isBadRequest());

        assertThat(companyRepository.findByCode("BAD/CODE")).isEmpty();
        long companyId = companyRepository.findByCode(companyCode).orElseThrow().id();
        assertThat(positionRepository.findByCompanyIdAndCode(companyId, "BAD?CODE")).isEmpty();
        assertThat(departmentRepository.findByCompanyIdAndCode(companyId, "BAD#CODE")).isEmpty();
        assertThat(userRepository.findByCompanyIdAndCode(companyId, "BAD CODE")).isEmpty();
    }

    @Test
    void valid_path_safe_codes_produce_canonical_locations() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String code = "C-" + suffix + "_1";
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Safe\","
                                + "\"emailDomain\":\"" + suffix + ".location.example\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(
                        "/api/v1/admin/companies/" + code.toUpperCase())));
    }

    @Test
    void surrounding_whitespace_is_trimmed_before_code_validation_and_location_creation() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String rawCode = " c-" + suffix + "_1 ";
        String canonicalCode = rawCode.trim().toUpperCase();

        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + rawCode + "\",\"name\":\"Trimmed\","
                                + "\"emailDomain\":\"" + suffix + ".trim.example\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(canonicalCode))
                .andExpect(header().string("Location", endsWith(
                        "/api/v1/admin/companies/" + canonicalCode)));
        assertThat(companyRepository.findByCode(canonicalCode)).isPresent();

        String maximumCode = "A".repeat(50);
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" " + maximumCode + " \",\"name\":\"Maximum\","
                                + "\"emailDomain\":\"" + suffix + ".maximum.example\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(maximumCode));

        String tooLongCode = "B".repeat(51);
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\" " + tooLongCode + " \",\"name\":\"Too Long\","
                                + "\"emailDomain\":\"" + suffix + ".too-long.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(companyRepository.findByCode(tooLongCode)).isEmpty();
    }

    @Test
    void validation_problem_has_stable_type_title_and_server_owned_trace_id() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .header("X-Trace-Id", "trace-validation-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"\",\"emailDomain\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
    }

    @Test
    void framework_routing_errors_use_the_same_problem_details_contract() throws Exception {
        mvc.perform(get("/api/v1/admin/not-a-route")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString(
                        "application/problem+json")))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/resource-not-found"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
        mvc.perform(delete("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/method-not-allowed"));
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType("text/plain").content("unsupported"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.type").value(
                        "https://auth-study.local/problems/unsupported-media-type"));
    }

    @Test
    void duplicate_sort_values_have_a_stable_code_tiebreaker_across_pages() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String firstInserted = "Z" + suffix;
        String secondInserted = "A" + suffix;
        createCompany(firstInserted, suffix.toLowerCase() + "-z.sort.example");
        createCompany(secondInserted, suffix.toLowerCase() + "-a.sort.example");

        String firstPage = mvc.perform(get("/api/v1/admin/companies")
                        .param("search", suffix).param("sort", "name").param("page", "0").param("size", "1")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        String secondPage = mvc.perform(get("/api/v1/admin/companies")
                        .param("search", suffix).param("sort", "name").param("page", "1").param("size", "1")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(firstPage, "$.content[0].code"))
                .isEqualTo(secondInserted);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(secondPage, "$.content[0].code"))
                .isEqualTo(firstInserted);
    }

    @Test
    void unsafe_path_code_uses_the_same_validation_problem_contract() throws Exception {
        mvc.perform(get("/api/v1/admin/companies/BAD.CODE/positions")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/validation-failed"));
    }

    @Test
    void user_list_does_not_load_accounts_one_by_one() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = "N" + suffix;
        createCompany(companyCode, suffix + ".nplusone.example");
        createUser(companyCode, suffix + ".nplusone.example", "U001");
        createUser(companyCode, suffix + ".nplusone.example", "U002");
        clearInvocations(accountRepository);

        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        verify(accountRepository, never()).findByUserId(anyLong());
    }

    @Test
    void list_endpoints_do_not_load_the_entire_tenant_collection_before_paging() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String companyCode = "D" + suffix;
        createCompany(companyCode, suffix + ".dbpage.example");
        createUser(companyCode, suffix + ".dbpage.example", "U001");
        clearInvocations(companyRepository, positionRepository, departmentRepository, membershipRepository);

        mvc.perform(get("/api/v1/admin/companies").header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/positions", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/departments", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users/U001/memberships", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)).andExpect(status().isOk());

        verify(companyRepository, never()).findAll();
        verify(positionRepository, never()).findAllByCompanyId(anyLong());
        verify(departmentRepository, never()).findAllByCompanyId(anyLong());
        verify(membershipRepository, never()).findAllByUserId(anyLong());
    }

    private void createCompany(String code, String domain) throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Acme\","
                                + "\"emailDomain\":\"" + domain + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createUser(String companyCode, String domain, String userCode) throws Exception {
        String local = userCode.toLowerCase();
        mvc.perform(post("/api/v1/admin/companies/{companyCode}/users", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + userCode + "\",\"employeeNumber\":\"E-" + userCode + "\","
                                + "\"name\":\"" + userCode + "\",\"loginEmail\":\"" + local + "@" + domain
                                + "\",\"phone\":\"010-0000-0000\","
                                + "\"hiredAt\":\"2026-08-20\",\"workplace\":\"Seoul\","
                                + "\"positionCode\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
    }
}
