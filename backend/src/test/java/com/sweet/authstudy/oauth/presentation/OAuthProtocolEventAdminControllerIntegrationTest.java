package com.sweet.authstudy.oauth.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import org.junit.jupiter.api.Test;

class OAuthProtocolEventAdminControllerIntegrationTest extends OAuthAdminTestSupport {
    void event(long companyId, String clientId, OAuthProtocolEvent.EventType type) {
        events.saveBestEffort(OAuthProtocolEvent.create(now,"test-correlation",type,OAuthProtocolEvent.Outcome.SUCCESS,clientId,subject,account.id(),companyId,null,null,OAuthProtocolEvent.Metadata.empty()));
    }
    @Test void tenant_filtered_cursor_is_stable_for_equal_timestamps_and_safe() throws Exception {
        event(company.id(),client.clientId(),OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_ISSUED);
        event(company.id(),client.clientId(),OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_EXCHANGED);
        event(other.id(),client.clientId(),OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_EXCHANGED);
        event(company.id(),otherClient.clientId(),OAuthProtocolEvent.EventType.AUTHORIZATION_CODE_EXCHANGED);
        String body=mvc.perform(get(path()+"/protocol-events").param("size","1").header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].eventType").value("AUTHORIZATION_CODE_EXCHANGED"))
            .andExpect(jsonPath("$.content[0].accountId").doesNotExist())
            .andExpect(jsonPath("$.content[0].code").doesNotExist())
            .andExpect(jsonPath("$.content[0].token").doesNotExist())
            .andExpect(jsonPath("$.content[0].secret").doesNotExist())
            .andExpect(jsonPath("$.content[0].verifier").doesNotExist())
            .andExpect(jsonPath("$.content[0].password").doesNotExist())
            .andExpect(jsonPath("$.hasNext").value(true)).andReturn().getResponse().getContentAsString();
        String cursor=com.jayway.jsonpath.JsonPath.read(body,"$.nextCursor");
        mvc.perform(get(path()+"/protocol-events").param("size","1").param("cursor",cursor).header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].eventType").value("AUTHORIZATION_CODE_ISSUED"))
            .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get(path()+"/protocol-events").param("type","AUTHORIZATION_CODE_EXCHANGED").param("outcome","SUCCESS").header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
    }
    @Test void invalid_filters_and_tenant_access_are_rejected() throws Exception {
        for(String[] param: new String[][]{{"type","TOKEN_ISSUED"},{"outcome","BAD"},{"size","101"},{"size","0"},{"page","1"},{"cursor","invalid"}})
            mvc.perform(get(path()+"/protocol-events").param(param[0],param[1]).header("Authorization",token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/companies/"+other.code()+"/oauth-clients/"+otherClient.clientId()+"/protocol-events").header("Authorization",token)).andExpect(status().isForbidden());
        mvc.perform(get(path().replace(client.clientId(),"missing")+"/protocol-events").header("Authorization",token)).andExpect(status().isNotFound());
    }
}
