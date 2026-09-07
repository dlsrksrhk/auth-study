package com.sweet.authstudy.oauth.presentation;

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
import com.sweet.authstudy.oauth.domain.*;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
abstract class OAuthAdminTestSupport {
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtTokenService tokens;
    @Autowired
    CompanyRepository companies;
    @Autowired
    PositionRepository positions;
    @Autowired
    UserRepository users;
    @Autowired
    AccountRepository accounts;
    @Autowired
    OAuthClientRepository clients;
    @Autowired
    OAuthSubjectRepository subjects;
    @Autowired
    OAuthConsentRepository consents;
    @Autowired
    OAuthProtocolEventRepository events;
    final Instant now = Instant.parse("2026-08-21T00:00:00Z");
    Company company, other;
    Account account;
    OAuthClient client, otherClient;
    UUID subject;
    String token, systemToken;

    @AfterEach
    void cleanupClients() {
        if (company == null || other == null) return;
        jdbc.update("delete from oauth_authorization where company_id in (?,?)", company.id(), other.id());
        jdbc.update("delete from oauth_client where company_id in (?,?)", company.id(), other.id());
        jdbc.update("delete from oauth_protocol_event where company_id in (?,?)", company.id(), other.id());
    }

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        company = companies.save(Company.create("C" + suffix, "Company", suffix.toLowerCase() + ".example", now));
        other = companies.save(Company.create("D" + suffix, "Other", "d" + suffix.toLowerCase() + ".example", now));
        Position position = positions.save(Position.create(company.id(), "EMP", "Employee", 1, 1, true, now));
        HrUser user = users.save(HrUser.create(company.id(), "ADMIN", "E-1", "Admin", "010", LocalDate.now(), "Seoul", null, position.id(), now));
        Account candidate = Account.createCompanyAccount(company.id(), user.id(), "admin@" + suffix.toLowerCase() + ".example", "unused-hash", now);
        candidate.addRole(AccountRole.COMPANY_ADMIN, now);
        account = accounts.save(candidate);
        subject = UUID.randomUUID();
        subjects.save(OAuthSubject.create(account.id(), subject, now));
        client = client(company, "c" + suffix);
        otherClient = client(other, "d" + suffix);
        token = "Bearer " + tokens.issue(new AuthenticatedAccount(account.id(), company.id(), user.id(), account.roles(), false), false).accessToken();
        systemToken = "Bearer " + tokens.issue(new AuthenticatedAccount(-1, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false), false).accessToken();
    }

    OAuthClient client(Company owner, String id) {
        return clients.save(OAuthClient.create(owner.id(), id, "Client", true, Set.of(URI.create("https://client.example/cb")), Set.of(), Set.of("openid"), OAuthClientTrust.CONSENT_REQUIRED, Set.of(), now));
    }

    String path() {
        return "/api/v1/admin/companies/" + company.code() + "/oauth-clients/" + client.clientId();
    }

    void consent() {
        consents.save(OAuthConsent.create(account.id(), client.id(), Set.of("openid"), now));
    }

    void grant(String id) {
        jdbc.update("insert into oauth_authorization(id,registered_client_id,subject,principal_account_id,company_id,authorization_grant_type,authorized_scopes,attributes,authenticated_at,status,created_at,expires_at) values(?,?,?,?,?,'authorization_code','openid','{}',?,'ACTIVE',?,?)", id, client.id(), subject, account.id(), company.id(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(3600)));
        jdbc.update("insert into oauth_refresh_token(authorization_id,refresh_token_hash,family_id,authorized_scopes,issued_at,expires_at) values(?,?,?,'openid',?,?)", id, UUID.randomUUID().toString().replace("-", "").repeat(2), UUID.randomUUID(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(3600)));
    }
}
