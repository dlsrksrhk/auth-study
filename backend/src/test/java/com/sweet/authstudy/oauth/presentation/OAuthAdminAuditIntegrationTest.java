package com.sweet.authstudy.oauth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OAuthAdminAuditIntegrationTest extends OAuthAdminTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.sweet.authstudy.audit.domain.AuditLogRepository auditRepository;
    @org.springframework.beans.factory.annotation.Autowired
    com.sweet.authstudy.oauth.application.OAuthClientService clientService;
    @org.springframework.beans.factory.annotation.Autowired
    com.sweet.authstudy.oauth.application.OAuthAdminService adminService;

    @Test void audit_storage_failure_rolls_back_client_creation() {
        var actor=new com.sweet.authstudy.authorization.AuthenticatedAccount(account.id(),company.id(),account.userId(),account.roles(),false);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(auditRepository).save(org.mockito.ArgumentMatchers.any());
        var command=new com.sweet.authstudy.oauth.application.OAuthClientCommands.CreateClient(company.code(),"Uncommitted",false,client.redirectUris(),java.util.Set.of(),java.util.Set.of("openid"),client.trust());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->clientService.create(actor,command)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(clients.findByCompanyId(company.id())).hasSize(1);
    }

    @Test void audit_storage_failure_rolls_back_consent_and_grant_revocation() {
        consent(); String id=java.util.UUID.randomUUID().toString(); grant(id);
        var actor=new com.sweet.authstudy.authorization.AuthenticatedAccount(account.id(),company.id(),account.userId(),account.roles(),false);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(auditRepository).save(org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->adminService.revokeConsent(actor,company.code(),client.clientId(),subject)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(consents.findByAccountIdAndRegisteredClientId(account.id(),client.id())).isPresent();
        assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?",String.class,id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select revoked_at is null from oauth_refresh_token where authorization_id=?",Boolean.class,id)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->adminService.revokeAuthorizations(actor,company.code(),client.clientId())).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?",String.class,id)).isEqualTo("ACTIVE");
    }

    @Test void audit_storage_failure_rolls_back_client_changes_and_rotation() {
        var actor=new com.sweet.authstudy.authorization.AuthenticatedAccount(account.id(),company.id(),account.userId(),account.roles(),false);
        var command=new com.sweet.authstudy.oauth.application.OAuthClientCommands.CreateClient(company.code(),"Original",false,client.redirectUris(),java.util.Set.of(),java.util.Set.of("openid"),client.trust());
        var created=clientService.create(actor,command);
        client=clients.findByClientId(created.client().clientId()).orElseThrow();
        String id=java.util.UUID.randomUUID().toString(); grant(id);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(auditRepository).save(org.mockito.ArgumentMatchers.any());
        var update=new com.sweet.authstudy.oauth.application.OAuthClientCommands.UpdateClient("Changed",client.redirectUris(),java.util.Set.of(),java.util.Set.of("openid"),client.trust(),null,client.version());
        for (Runnable action: java.util.List.<Runnable>of(
                ()->clientService.update(actor,client.clientId(),update),
                ()->clientService.rotateSecret(actor,client.clientId()),
                ()->clientService.revokeSecret(actor,client.clientId()),
                ()->clientService.disable(actor,client.clientId(),client.version()),
                ()->clientService.enable(actor,client.clientId(),client.version()))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(action::run).hasRootCauseInstanceOf(IllegalStateException.class);
            var unchanged=clients.findByClientId(client.clientId()).orElseThrow();
            assertThat(unchanged.displayName()).isEqualTo("Original");
            assertThat(unchanged.status()).isEqualTo(com.sweet.authstudy.oauth.domain.OAuthClientStatus.ACTIVE);
            assertThat(unchanged.version()).isEqualTo(client.version());
            assertThat(unchanged.secrets()).hasSize(1);
            assertThat(unchanged.secrets().iterator().next().revokedAt()).isNull();
            assertThat(jdbc.queryForObject("select status from oauth_authorization where id=?",String.class,id)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select revoked_at is null from oauth_refresh_token where authorization_id=?",Boolean.class,id)).isTrue();
        }
    }

    @Test void client_mutations_are_audited_without_secrets() throws Exception {
        String created=mvc.perform(post("/api/v1/admin/companies/"+company.code()+"/oauth-clients").header("Authorization",token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Client\",\"publicClient\":false,\"redirectUris\":[\"https://client.example/cb\"],\"scopes\":[\"openid\"],\"trust\":\"CONSENT_REQUIRED\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=com.jayway.jsonpath.JsonPath.read(created,"$.client.clientId");
        String secret=com.jayway.jsonpath.JsonPath.read(created,"$.oneTimeSecret");
        String base=path().replace(client.clientId(),id);
        mvc.perform(put(base).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"displayName\":\"Updated\",\"redirectUris\":[\"https://client.example/cb\"],\"scopes\":[\"openid\"],\"trust\":\"CONSENT_REQUIRED\",\"version\":0}"))
            .andExpect(status().isOk());
        mvc.perform(post(base+"/rotate-secret").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(post(base+"/revoke-secret").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.activeSecretHint").doesNotExist()).andExpect(jsonPath("$.oneTimeSecret").doesNotExist());
        for(String action: new String[]{"disable","enable"}) {
            String current=mvc.perform(get(base).header("Authorization",token)).andReturn().getResponse().getContentAsString();
            Number version=com.jayway.jsonpath.JsonPath.read(current,"$.version");
            mvc.perform(post(base+"/"+action).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}")).andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForList("select action from audit_logs where company_id=?",String.class,company.id()))
            .containsExactlyInAnyOrder("OAUTH_CLIENT_CREATED","OAUTH_CLIENT_UPDATED","OAUTH_CLIENT_SECRET_ROTATED","OAUTH_CLIENT_SECRET_REVOKED","OAUTH_CLIENT_DISABLED","OAUTH_CLIENT_ENABLED");
        assertThat(jdbc.queryForList("select details::text from audit_logs where company_id=?",String.class,company.id()).toString()).doesNotContain(secret,"secret","token","password");
    }
    @Test void public_client_revoke_secret_is_conflict_and_foreign_tenant_is_forbidden() throws Exception {
        mvc.perform(post(path()+"/revoke-secret").header("Authorization",token)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/companies/"+other.code()+"/oauth-clients/"+otherClient.clientId()+"/revoke-secret").header("Authorization",token)).andExpect(status().isForbidden());
    }
}
