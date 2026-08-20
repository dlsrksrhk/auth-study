package com.sweet.authstudy.presentation;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.UUID;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AdminApiContractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
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
    void very_large_zero_based_page_is_an_empty_page_instead_of_an_internal_error() throws Exception {
        mvc.perform(get("/api/v1/admin/companies")
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }
}
