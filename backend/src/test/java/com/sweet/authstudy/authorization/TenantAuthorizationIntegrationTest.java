package com.sweet.authstudy.authorization;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
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

    private String companyAdminToken;
    private String acmeCode;
    private String betaCode;
    private String userCode;

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
        companyAdminToken = jwtTokenService.issue(new AuthenticatedAccount(
                account.id(), acme.id(), user.id(), account.roles(), false), false).accessToken();
    }

    @Test
    void company_admin_cannot_read_another_company() throws Exception {
        mvc.perform(get("/api/v1/admin/companies/{companyCode}/users", betaCode)
                        .header(AUTHORIZATION, "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
}
