package com.sweet.authstudy.audit;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class AuditApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired Clock clock;

    private String token;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(Account.createSystemAdmin(
                "audit-api-" + UUID.randomUUID() + "@auth-study.local",
                passwordEncoder.encode("SystemPassword1234!"), false, clock.instant()));
        token = jwtTokenService.issue(new AuthenticatedAccount(
                account.id(), null, null, account.roles(), false), false).accessToken();
    }

    @Test
    void lists_company_scoped_logs_with_trace_filters_pagination_and_safe_sort() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = ("Q" + suffix).toUpperCase();
        String traceId = "audit-trace-" + suffix;
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + token)
                        .header("X-Trace-Id", traceId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Audit API\","
                                + "\"emailDomain\":\"" + suffix + ".audit-api.example\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/admin/companies/{companyCode}/audit-logs", code)
                        .header(AUTHORIZATION, "Bearer " + token)
                        .param("search", "company")
                        .param("action", "COMPANY_CREATE")
                        .param("success", "true")
                        .param("page", "0").param("size", "1").param("sort", "occurredAt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].traceId").value(traceId))
                .andExpect(jsonPath("$.content[0].details.passwordHash").doesNotExist());

        mvc.perform(get("/api/v1/admin/companies/{companyCode}/audit-logs", code)
                        .header(AUTHORIZATION, "Bearer " + token).param("sort", "details"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void exposes_no_audit_update_or_delete_endpoint() throws Exception {
        mvc.perform(put("/api/v1/admin/companies/NOPE/audit-logs/1")
                        .header(AUTHORIZATION, "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/companies/NOPE/audit-logs/1")
                        .header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
