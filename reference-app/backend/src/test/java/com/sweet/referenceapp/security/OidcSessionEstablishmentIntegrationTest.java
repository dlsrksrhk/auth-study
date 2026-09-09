package com.sweet.referenceapp.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

class OidcSessionEstablishmentIntegrationTest extends LocalLoginHttpTestSupport {
    @Autowired SecurityFilterChain chain;
    @Autowired OAuth2AuthorizedClientRepository clients;

    @Test void saveContextFailureAfterPersistenceKeepsCommittedBootstrapButDiscardsSessionAndClient() throws Exception {
        ISSUER.userInfoClaims = Map.of("sub", ISSUER.subject, HR_ROLES, List.of("COMPANY_ADMIN"));
        var filter = chain.getFilters().stream().filter(OAuth2LoginAuthenticationFilter.class::isInstance)
                .map(OAuth2LoginAuthenticationFilter.class::cast).findFirst().orElseThrow();
        var original = (SecurityContextRepository) ReflectionTestUtils.getField(filter, "securityContextRepository");
        var failing = mock(SecurityContextRepository.class);
        var capturedSession = new AtomicReference<HttpSession>();
        var rotatedCookie = new AtomicReference<String>();
        doAnswer(invocation -> {
            SecurityContext context = invocation.getArgument(0);
            HttpServletRequest request = invocation.getArgument(1);
            HttpServletResponse response = invocation.getArgument(2);
            original.saveContext(context, request, response);
            var session = request.getSession(false);
            capturedSession.set(session);
            rotatedCookie.set("RP_SESSION=" + session.getId());
            assertThat(context.getAuthentication().isAuthenticated()).isTrue();
            assertThat(clients.<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>loadAuthorizedClient("reference-app", context.getAuthentication(), request)).isNotNull();
            assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
            throw new IllegalStateException("private persistence failure");
        }).when(failing).saveContext(any(), any(), any());
        filter.setSecurityContextRepository(failing);
        try {
            var pending = begin(null);
            var response = callback(pending, "code=valid-code&state=" + pending.state());
            assertThat(capturedSession.get()).isNotNull();
            assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
            var id = jdbc.queryForObject("select id from app_user", UUID.class);
            assertThat(jdbc.queryForObject("select bootstrapped_user_id from app_bootstrap_state", UUID.class)).isEqualTo(id);
            assertThat(jdbc.queryForList("select role from app_user_role where app_user_id = ?", String.class, id))
                    .containsExactlyInAnyOrder("APP_USER", "APP_ADMIN");
            assertFailure(response, pending.cookie(), "oidc_login_failed");
            assertThatThrownBy(() -> capturedSession.get().getAttribute("SPRING_SECURITY_CONTEXT"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(send("GET", "/bff/profile", rotatedCookie.get(), "").statusCode()).isEqualTo(401);
            assertThat(send("GET", "/bff/test-client?exchange=1", rotatedCookie.get(), "").statusCode()).isEqualTo(401);
            assertThat(send("GET", "/bff/session", rotatedCookie.get(), "").body()).isEqualTo("{\"authenticated\":false}");
            assertThat(response.body() + response.headers().map()).doesNotContain("private persistence failure");
        } finally {
            filter.setSecurityContextRepository(original);
        }
    }
}
