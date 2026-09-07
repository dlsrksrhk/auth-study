package com.sweet.authstudy.oauth.presentation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthConsentAdminControllerIntegrationTest extends OAuthAdminTestSupport {
    @Test
    void consent_page_exposes_only_public_subject_and_scopes() throws Exception {
        consent();
        mvc.perform(get(path() + "/consents").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subject").value(subject.toString()))
                .andExpect(jsonPath("$.content[0].approvedScopes[0]").value("openid"))
                .andExpect(jsonPath("$.content[0].grantedAt").exists())
                .andExpect(jsonPath("$.content[0].updatedAt").exists())
                .andExpect(jsonPath("$.content[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].loginEmail").doesNotExist());
        mvc.perform(get(path() + "/consents").param("page", "1").param("size", "1").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void tenant_and_missing_resource_errors_are_distinct() throws Exception {
        mvc.perform(get("/api/v1/admin/companies/" + other.code() + "/oauth-clients/" + otherClient.clientId() + "/consents").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get(path().replace(client.clientId(), "missing") + "/consents").header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get(path() + "/consents").param("size", "101").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(delete(path() + "/consents/" + java.util.UUID.randomUUID()).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(delete(path() + "/consents/invalid").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get(path() + "/consents").header("Authorization", systemToken)).andExpect(status().isOk());
        String foreign = "/api/v1/admin/companies/" + other.code() + "/oauth-clients/" + otherClient.clientId();
        mvc.perform(delete(foreign + "/consents/" + subject).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post(foreign + "/revoke-authorizations").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void consent_delete_revokes_grants_and_refresh_and_is_audited() throws Exception {
        consent();
        String id = java.util.UUID.randomUUID().toString();
        grant(id);
        var originalAccount = account;
        var originalSubject = subject;
        var originalClient = client;
        var position = positions.save(com.sweet.authstudy.hr.position.domain.Position.create(company.id(), "OTHER", "Other", 2, 2, true, now));
        var user = users.save(com.sweet.authstudy.hr.user.domain.HrUser.create(company.id(), "OTHER", "E-2", "Other", "010", java.time.LocalDate.now(), "Seoul", null, position.id(), now));
        account = accounts.save(com.sweet.authstudy.identity.domain.Account.createCompanyAccount(company.id(), user.id(), "other@" + company.emailDomain(), "unused-hash", now));
        subject = java.util.UUID.randomUUID();
        subjects.save(com.sweet.authstudy.oauth.domain.OAuthSubject.create(account.id(), subject, now));
        consent();
        String otherSubjectGrant = java.util.UUID.randomUUID().toString();
        grant(otherSubjectGrant);
        account = originalAccount;
        subject = originalSubject;
        client = client(company, "same-tenant-" + java.util.UUID.randomUUID());
        consent();
        String otherClientGrant = java.util.UUID.randomUUID().toString();
        grant(otherClientGrant);
        client = originalClient;
        mvc.perform(delete(path() + "/consents/" + subject).header("Authorization", token)).andExpect(status().isNoContent());
        assertThat(consents.findByAccountIdAndRegisteredClientId(account.id(), client.id())).isEmpty();
        assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?", String.class, id)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select revoked_at is not null from oauth_refresh_token where authorization_id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='OAUTH_CONSENT_REVOKED' and company_id=?", Long.class, company.id())).isEqualTo(1);
        for (String unaffected : java.util.List.of(otherSubjectGrant, otherClientGrant)) {
            assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?", String.class, unaffected)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select revoked_at is null from oauth_refresh_token where authorization_id=?", Boolean.class, unaffected)).isTrue();
        }
    }

    @Test
    void client_revocation_preserves_consent() throws Exception {
        consent();
        String id = java.util.UUID.randomUUID().toString();
        grant(id);
        mvc.perform(post(path() + "/revoke-authorizations").header("Authorization", token)).andExpect(status().isNoContent());
        assertThat(consents.findByAccountIdAndRegisteredClientId(account.id(), client.id())).isPresent();
        assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?", String.class, id)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select revoked_at is not null from oauth_refresh_token where authorization_id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='OAUTH_AUTHORIZATIONS_REVOKED' and company_id=?", Long.class, company.id())).isEqualTo(1);
    }

    @Test
    void consent_query_filters_mismatched_tenant_rows_and_other_clients() throws Exception {
        consent();
        consents.save(com.sweet.authstudy.oauth.domain.OAuthConsent.create(account.id(), otherClient.id(), java.util.Set.of("openid"), now));
        String foreign = "/api/v1/admin/companies/" + other.code() + "/oauth-clients/" + otherClient.clientId();
        mvc.perform(get(foreign + "/consents").header("Authorization", systemToken)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete(foreign + "/consents/" + subject).header("Authorization", systemToken)).andExpect(status().isNotFound());
        mvc.perform(get(path() + "/consents").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
