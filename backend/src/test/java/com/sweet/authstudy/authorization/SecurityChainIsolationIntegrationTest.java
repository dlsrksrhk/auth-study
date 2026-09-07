package com.sweet.authstudy.authorization;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class SecurityChainIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void production_context_has_exactly_one_project_oauth_jwk_source() {
        assertThat(applicationContext.getBeanNamesForType(JWKSource.class)).hasSize(1);
    }

    @Test
    void idpLoginCreatesOnlyIdpSessionWhileApiRemainsStateless() throws Exception {
        mockMvc.perform(get("/idp/login"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("IDP_AUTH_SESSION"));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("IDP_AUTH_SESSION"));
    }

    @Test
    void idpEndpointsRejectCrossOriginUnsafeRequests() throws Exception {
        mockMvc.perform(post("/idp/login")
                        .with(csrf())
                        .header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void browser_authorization_post_requires_issuer_origin_but_token_post_does_not() throws Exception {
        mockMvc.perform(post("/oauth2/authorize")
                        .with(csrf())
                        .header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/oauth2/token"))
                .andExpect(status().isBadRequest());
    }
}
