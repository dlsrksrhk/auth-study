package com.sweet.authstudy.oauth.presentation;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyRepository;
import com.sweet.authstudy.hr.position.domain.Position;
import com.sweet.authstudy.hr.position.domain.PositionRepository;
import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.identity.application.JwtTokenService;
import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OAuthClientAdminControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired CompanyRepository companies;
    @Autowired PositionRepository positions;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    private String systemToken;
    private String companyToken;
    private String companyCode;
    private String otherCode;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Company company = companies.save(Company.create("A" + suffix, "Acme", suffix.toLowerCase() + ".example", clock.instant()));
        companies.save(Company.create("B" + suffix, "Other", "b" + suffix.toLowerCase() + ".example", clock.instant()));
        companyCode = company.code();
        otherCode = "B" + suffix;
        Position position = positions.save(Position.create(company.id(), "EMPLOYEE", "Employee", 1, 1, true, clock.instant()));
        HrUser user = users.save(HrUser.create(company.id(), "ADMIN", "E-1", "Admin", "010", LocalDate.now(),
                "Seoul", null, position.id(), clock.instant()));
        Account account = Account.createCompanyAccount(company.id(), user.id(), "admin@" + suffix.toLowerCase() + ".example",
                passwordEncoder.encode("Password1234!"), clock.instant());
        account.addRole(AccountRole.COMPANY_ADMIN, clock.instant());
        account = accounts.save(account);
        companyToken = token(new AuthenticatedAccount(account.id(), company.id(), user.id(), account.roles(), false));
        systemToken = token(new AuthenticatedAccount(-1, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false));
    }

    @Test
    void system_admin_can_list_oauth_clients() throws Exception {
        String own = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(true, "CONSENT_REQUIRED")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String other = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", otherCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(true, "CONSENT_REQUIRED")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ownId = com.jayway.jsonpath.JsonPath.read(own, "$.client.clientId");
        String otherId = com.jayway.jsonpath.JsonPath.read(other, "$.client.clientId");

        mvc.perform(get("/api/v1/admin/oauth-clients")
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[*].clientId", org.hamcrest.Matchers.hasItems(ownId, otherId)));
        mvc.perform(get("/api/v1/admin/oauth-clients").param("companyCode", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].clientId", org.hamcrest.Matchers.hasItem(ownId)))
                .andExpect(jsonPath("$.content[*].clientId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(otherId))));
        mvc.perform(get("/api/v1/admin/oauth-clients").header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void company_admin_is_tenant_scoped_and_cannot_grant_trusted_status() throws Exception {
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", otherCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(false, "CONSENT_REQUIRED")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(false, "TRUSTED_FIRST_PARTY")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients", otherCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void invalid_fields_and_missing_client_have_stable_errors() throws Exception {
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\",\"publicClient\":true,\"redirectUris\":[],"
                                + "\"postLogoutRedirectUris\":[],\"scopes\":[],\"trust\":\"CONSENT_REQUIRED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        org.hamcrest.Matchers.hasItems("displayName", "redirectUris", "scopes")));
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicClient\":true,\"postLogoutRedirectUris\":[],"
                                + "\"trust\":\"CONSENT_REQUIRED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        org.hamcrest.Matchers.hasItems("displayName", "redirectUris", "scopes")));
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Client\",\"publicClient\":true,"
                                + "\"redirectUris\":[\"https://client.example/cb\"],"
                                + "\"postLogoutRedirectUris\":[],\"scopes\":[\"openid\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients/missing-client", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void confidential_secret_is_one_time_and_public_clients_have_no_secret() throws Exception {
        String confidential = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(false, "CONSENT_REQUIRED")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.oneTimeSecret").isString())
                .andReturn().getResponse().getContentAsString();
        String clientId = com.jayway.jsonpath.JsonPath.read(confidential, "$.client.clientId");
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients/{id}", companyCode, clientId)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.oneTimeSecret").doesNotExist())
                .andExpect(jsonPath("$.secretHash").doesNotExist()).andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].oneTimeSecret").doesNotExist());
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients/{id}/rotate-secret", companyCode, clientId)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.oneTimeSecret").isString())
                .andExpect(jsonPath("$.client.oneTimeSecret").doesNotExist());
        String publicBody = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(true, "CONSENT_REQUIRED")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.oneTimeSecret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String publicId = com.jayway.jsonpath.JsonPath.read(publicBody, "$.client.clientId");
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients/{id}/rotate-secret", companyCode, publicId)
                        .header(AUTHORIZATION, "Bearer " + companyToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void update_preserves_status_and_status_actions_require_current_version() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(true, "TRUSTED_FIRST_PARTY")))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.client.clientId");
        Number version = com.jayway.jsonpath.JsonPath.read(created, "$.client.version");
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients/{id}/disable", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients/{id}/enable", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict());
        String current = mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients/{id}", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + systemToken)).andReturn().getResponse().getContentAsString();
        Number currentVersion = com.jayway.jsonpath.JsonPath.read(current, "$.version");
        mvc.perform(put("/api/v1/admin/companies/{code}/oauth-clients/{id}", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Updated\",\"redirectUris\":[\"https://client.example/cb\"],"
                                + "\"postLogoutRedirectUris\":[],\"scopes\":[\"openid\"],"
                                + "\"trust\":\"TRUSTED_FIRST_PARTY\",\"version\":" + currentVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.oneTimeSecret").doesNotExist());
        mvc.perform(get("/api/v1/admin/companies/{code}/oauth-clients/{id}", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + systemToken))
                .andExpect(jsonPath("$.oneTimeSecret").doesNotExist());
    }

    @Test
    void company_admin_cannot_submit_trusted_value_when_updating_existing_trusted_client() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/companies/{code}/oauth-clients", companyCode)
                        .header(AUTHORIZATION, "Bearer " + systemToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(true, "TRUSTED_FIRST_PARTY")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.client.clientId");
        Number version = com.jayway.jsonpath.JsonPath.read(created, "$.client.version");
        mvc.perform(put("/api/v1/admin/companies/{code}/oauth-clients/{id}", companyCode, id)
                        .header(AUTHORIZATION, "Bearer " + companyToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Forbidden edit\",\"redirectUris\":[\"https://client.example/cb\"],"
                                + "\"postLogoutRedirectUris\":[],\"scopes\":[\"openid\"],"
                                + "\"trust\":\"TRUSTED_FIRST_PARTY\",\"version\":" + version + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private String token(AuthenticatedAccount actor) { return jwtTokenService.issue(actor, false).accessToken(); }

    private String createJson(boolean publicClient, String trust) {
        return "{\"displayName\":\"Client\",\"publicClient\":" + publicClient
                + ",\"redirectUris\":[\"https://client.example/cb\"],\"postLogoutRedirectUris\":[],"
                + "\"scopes\":[\"openid\"],\"trust\":\"" + trust + "\"}";
    }
}
