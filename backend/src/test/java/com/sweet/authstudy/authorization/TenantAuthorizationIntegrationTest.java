package com.sweet.authstudy.authorization;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.matchesPattern;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentRepository;
import com.sweet.authstudy.hr.membership.domain.DepartmentMembership;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class TenantAuthorizationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CompanyRepository companyRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Autowired ActorContext actorContext;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired UserService userService;

    private String companyAdminToken;
    private String acmeCode;
    private String betaCode;
    private String userCode;
    private String targetAdminCode;
    private long targetMembershipId;
    private AuthenticatedAccount companyAdminActor;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        acmeCode = "A" + suffix;
        betaCode = "B" + suffix;
        userCode = "U" + suffix;
        Company acme = companyRepository.save(Company.create(
                acmeCode, "Acme", suffix.toLowerCase() + ".acme.example", clock.instant()));
        companyRepository.save(Company.create(
                betaCode, "Beta", suffix.toLowerCase() + ".beta.example", clock.instant()));
        Position position = positionRepository.save(Position.create(
                acme.id(), "EMPLOYEE", "Employee", 10, 10, true, clock.instant()));
        HrUser user = userRepository.save(HrUser.create(
                acme.id(), userCode, "E-" + suffix, "Admin", "010-0000-0000",
                LocalDate.of(2026, 8, 20), "Seoul", null, position.id(), clock.instant()));
        Account account = Account.createCompanyAccount(
                acme.id(), user.id(), "admin@" + suffix.toLowerCase() + ".acme.example",
                passwordEncoder.encode("Temporary1234!"), clock.instant());
        account.addRole(AccountRole.COMPANY_ADMIN, clock.instant());
        account = accountRepository.save(account);
        companyAdminActor = new AuthenticatedAccount(
                account.id(), acme.id(), user.id(), account.roles(), false);
        companyAdminToken = jwtTokenService.issue(companyAdminActor, false).accessToken();

        targetAdminCode = "T" + suffix;
        HrUser target = userRepository.save(HrUser.create(
                acme.id(), targetAdminCode, "T-" + suffix, "Target Admin", "010-1111-1111",
                LocalDate.of(2026, 8, 20), "Seoul", null, position.id(), clock.instant()));
        Account targetAccount = Account.createCompanyAccount(
                acme.id(), target.id(), "target@" + suffix.toLowerCase() + ".acme.example",
                passwordEncoder.encode("Temporary1234!"), clock.instant());
        targetAccount.addRole(AccountRole.COMPANY_ADMIN, clock.instant());
        accountRepository.save(targetAccount);
        Department department = departmentRepository.save(Department.create(
                acme.id(), null, "DEV", "Development", clock.instant()));
        targetMembershipId = membershipRepository.save(DepartmentMembership.create(
                acme.id(), target.id(), department.id(), DepartmentRole.MEMBER, true,
                Instant.parse("2026-08-20T00:00:00Z"), clock.instant())).id();
    }

    @Test
    void company_admin_cannot_read_another_company() throws Exception {
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", betaCode)
                        .header(AUTHORIZATION, "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/forbidden"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void only_system_admin_can_assign_company_admin_role() throws Exception {
        mvc.perform(put("/api/v1/admin/companies/{companyCode}/users/{userCode}/admin-role",
                        acmeCode, userCode)
                        .header(AUTHORIZATION, "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void inactive_company_token_cannot_use_its_own_admin_api() throws Exception {
        Company company = companyRepository.findByCode(acmeCode).orElseThrow();
        company.update(company.name(), CompanyStatus.INACTIVE, clock.instant());
        companyRepository.save(company);

        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", acmeCode)
                        .header(AUTHORIZATION, "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void actor_context_rejects_every_principal_type_except_authenticated_account() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken("plain-principal", "credentials"));
        SecurityContextHolder.setContext(context);

        assertThatThrownBy(actorContext::current)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void company_admin_cannot_mutate_another_company_admin_as_an_ordinary_user() throws Exception {
        var requests = java.util.List.of(
                put("/api/v1/admin/companies/{companyCode}/users/{userCode}", acmeCode, targetAdminCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"phone\":\"010-2222-2222\","
                                + "\"hiredAt\":\"2026-08-20\",\"workplace\":\"Busan\","
                                + "\"positionCode\":\"EMPLOYEE\",\"version\":0}"),
                put("/api/v1/admin/companies/{companyCode}/users/{userCode}/status", acmeCode, targetAdminCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\",\"version\":0}"),
                post("/api/v1/admin/companies/{companyCode}/users/{userCode}/temporary-password",
                        acmeCode, targetAdminCode),
                post("/api/v1/admin/companies/{companyCode}/users/{userCode}/memberships",
                        acmeCode, targetAdminCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departmentCode\":\"DEV\",\"role\":\"MEMBER\","
                                + "\"primary\":false,\"startedAt\":\"2026-08-21T00:00:00Z\"}"),
                put("/api/v1/admin/companies/{companyCode}/users/{userCode}/memberships/{membershipId}",
                        acmeCode, targetAdminCode, targetMembershipId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HEAD\",\"primary\":true,\"version\":0}"),
                delete("/api/v1/admin/companies/{companyCode}/users/{userCode}/memberships/{membershipId}",
                        acmeCode, targetAdminCode, targetMembershipId).param("version", "0"));

        for (var request : requests) {
            mvc.perform(request.header(AUTHORIZATION, "Bearer " + companyAdminToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void application_service_also_blocks_company_admin_target_mutation() {
        assertThatThrownBy(() -> userService.resetTemporaryPassword(
                companyAdminActor, acmeCode, targetAdminCode))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void application_service_also_blocks_cross_tenant_reads() {
        assertThatThrownBy(() -> userService.list(
                companyAdminActor, betaCode, "", null, 0, 20, "code"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void method_secured_system_admin_endpoints_reject_company_admin() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header(AUTHORIZATION, "Bearer " + companyAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NEWCO\",\"name\":\"New\",\"emailDomain\":\"new.example\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void security_problem_uses_server_owned_trace_id_and_preserves_bearer_challenge() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("X-Trace-Id", "trace-security-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")))
                .andExpect(jsonPath("$.type").value("https://auth-study.local/problems/unauthenticated"))
                .andExpect(jsonPath("$.title").value("Authentication required"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }
}
