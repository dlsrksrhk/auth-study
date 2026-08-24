package com.sweet.authstudy.oauth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.sweet.authstudy.support.PostgresContainerConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, IdpBrowserFlowIntegrationTest.MutableClockConfiguration.class})
@ActiveProfiles("test")
class IdpBrowserFlowIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://rp.example/callback?source=idp");
    private static final String PASSWORD = "BrowserLogin1234!";
    private static final String CHANGED_PASSWORD = "BrowserChanged1234!";
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final Instant BASE_TIME = Instant.parse("2026-08-24T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @MockitoSpyBean private com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository
            authorizationRepository;
    @MockitoSpyBean private com.sweet.authstudy.oauth.application.OAuthConsentService consentService;

    @AfterEach
    void resetClock() {
        clock.set(BASE_TIME);
    }

    @Test
    void real_authorize_login_consent_callback_preserves_request_and_uses_minimal_idp_session() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of(
                "openid", "profile", "email", "hr.company", "hr.organization", "hr.roles"));
        MockHttpSession session = new MockHttpSession();
        String rpState = "rp-state-" + UUID.randomUUID();
        String nonce = "nonce-" + UUID.randomUUID();

        String loginLocation = beginAuthorization(
                fixture, session, Set.of("openid", "profile", "email"), rpState, nonce);
        assertThat(URI.create(loginLocation).getPath()).isEqualTo("/idp/login");

        LoginPage loginPage = loginPage(session);
        String oldSessionId = session.getId();
        MvcResult loggedIn = postLogin(session, loginPage.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        assertCookieIsLocalDevelopmentSession(loggedIn);
        URI resumed = URI.create(loggedIn.getResponse().getHeader("Location"));
        assertThat(resumed.getPath()).isEqualTo("/oauth2/authorize");
        assertThat(query(resumed, "client_id")).isEqualTo(fixture.clientId());
        assertThat(query(resumed, "redirect_uri")).isEqualTo(CALLBACK.toString());
        assertThat(query(resumed, "state")).isEqualTo(rpState);
        assertThat(query(resumed, "nonce")).isEqualTo(nonce);
        assertThat(query(resumed, "code_challenge")).isEqualTo(challenge(VERIFIER));
        assertThat(query(resumed, "code_challenge_method")).isEqualTo("S256");

        Authentication authentication = sessionAuthentication(session);
        assertThat(authentication.getName()).isEqualTo(Long.toString(fixture.accountId()));
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getClass().getSimpleName()).isEqualTo("IdpSessionAuthentication");
        assertThat(sessionValues(session)).doesNotContain(
                fixture.email(), PASSWORD, VERIFIER, "access_token", "refresh_token", "client-secret");
        assertThat(jdbcClient.sql("select count(*) from refresh_tokens where account_id = :accountId")
                .param("accountId", fixture.accountId()).query(Long.class).single()).isZero();

        ConsentPage consent = followToConsent(session, loggedIn.getResponse().getHeader("Location"));
        assertThat(consent.html()).contains(
                fixture.clientDisplayName(), "기본 식별", "프로필", "이메일");
        assertThat(consent.html()).contains("action=\"/oauth2/authorize\"");
        assertThat(jdbcClient.sql("""
                        select count(*) from oauth_authorization
                         where server_state_hash = :stateHash
                           and attributes -> 'authorizationRequest' ->> 'redirectUri' = :redirect
                           and attributes -> 'authorizationRequest' ->> 'rpState' = :rpState
                           and attributes -> 'authorizationRequest' ->> 'nonce' = :nonce
                           and attributes -> 'authorizationRequest' ->> 'codeChallenge' = :challenge
                           and attributes -> 'authorizationRequest' ->> 'codeChallengeMethod' = 'S256'
                        """).param("stateHash", sha256(consent.serverState()))
                .param("redirect", CALLBACK.toString()).param("rpState", rpState)
                .param("nonce", nonce).param("challenge", challenge(VERIFIER))
                .query(Long.class).single()).isEqualTo(1L);

        URI callback = approve(session, fixture, consent.serverState(), "openid", "profile", "email");
        assertCallback(callback, rpState);
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void password_change_is_mandatory_and_resumes_only_after_the_existing_password_policy_succeeds()
            throws Exception {
        Fixture fixture = fixture(true, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = new MockHttpSession();
        String rpState = "password-state-" + UUID.randomUUID();
        String original = beginAuthorization(fixture, session, Set.of("openid", "profile"), rpState, "password-nonce");

        LoginPage page = loginPage(session);
        MvcResult loggedIn = postLogin(session, page.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(loggedIn.getResponse().getHeader("Location")).isEqualTo("/idp/password");

        mockMvc.perform(get(original).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .isEqualTo("/idp/password"));

        MvcResult passwordPage = mockMvc.perform(get("/idp/password").session(session))
                .andExpect(status().isOk()).andReturn();
        String passwordFlowId = hidden(passwordPage.getResponse().getContentAsString(), "flowId");
        String sessionBeforeChange = session.getId();

        mockMvc.perform(post("/idp/password").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", passwordFlowId)
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("새 비밀번호가 정책을 충족하지 않습니다"));

        MvcResult refreshedPasswordPage = mockMvc.perform(get("/idp/password").session(session))
                .andExpect(status().isOk()).andReturn();
        passwordFlowId = hidden(refreshedPasswordPage.getResponse().getContentAsString(), "flowId");
        clock.set(BASE_TIME.plus(Duration.ofMinutes(10)));
        MvcResult changed = mockMvc.perform(post("/idp/password").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", passwordFlowId)
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", CHANGED_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(session.getId()).isNotEqualTo(sessionBeforeChange);
        assertThat(((IdpSessionAuthentication) sessionAuthentication(session)).authenticatedAt())
                .isEqualTo(BASE_TIME);
        URI resumed = URI.create(changed.getResponse().getHeader("Location"));
        assertThat(query(resumed, "state")).isEqualTo(rpState);
        assertThat(jdbcClient.sql("select must_change_password from accounts where id = :id")
                .param("id", fixture.accountId()).query(Boolean.class).single()).isFalse();
        ConsentPage consent = followToConsent(session, resumed.toString());
        assertCallback(approve(session, fixture, consent.serverState(), "openid", "profile"), rpState);
    }

    @Test
    void consent_is_reused_incremental_and_deny_keeps_the_previous_grant_untouched() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile", "email"));
        MockHttpSession session = loginFor(fixture);

        ConsentPage first = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "first", "nonce-first");
        approve(session, fixture, first.serverState(), "openid", "profile");

        MvcResult reused = performAuthorization(
                fixture, session, Set.of("openid", "profile"), "reuse", "nonce-reuse")
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(URI.create(reused.getResponse().getHeader("Location")).getHost()).isEqualTo("rp.example");

        ConsentPage incremental = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile", "email"), "deny-state", "nonce-deny");
        assertThat(incremental.html()).contains("모든 요청 권한", "새로 요청된 권한", "이메일");
        MvcResult denied = mockMvc.perform(post("/idp/consent/deny").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId())
                        .param("state", incremental.serverState()))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI deniedCallback = URI.create(denied.getResponse().getHeader("Location"));
        assertThat(query(deniedCallback, "error")).isEqualTo("access_denied");
        assertThat(query(deniedCallback, "state")).isEqualTo("deny-state");
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile");

        mockMvc.perform(post("/idp/consent/deny").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId())
                        .param("state", incremental.serverState()))
                .andExpect(status().isConflict());

        ConsentPage approveIncremental = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile", "email"), "incremental", "nonce-incremental");
        approve(session, fixture, approveIncremental.serverState(), "openid", "profile", "email");
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void incremental_approval_extends_an_older_scope_not_present_in_the_new_request() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile", "email"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage email = authorizeAuthenticated(
                fixture, session, Set.of("openid", "email"), "email-first", "email-first-nonce");
        assertCallback(approve(session, fixture, email.serverState(), "openid", "email"), "email-first");

        ConsentPage profile = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "profile-second", "profile-second-nonce");
        assertCallback(approve(session, fixture, profile.serverState(), "openid", "profile"), "profile-second");

        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void consent_required_openid_only_requires_first_consent_and_skips_only_after_real_approval()
            throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MockHttpSession session = loginFor(fixture);

        ConsentPage first = authorizeAuthenticated(
                fixture, session, Set.of("openid"), "openid-first", "openid-nonce-first");
        assertThat(first.html()).contains(fixture.clientDisplayName(), "기본 식별");
        assertCallback(approve(session, fixture, first.serverState(), "openid"), "openid-first");

        MvcResult reused = performAuthorization(
                fixture, session, Set.of("openid"), "openid-reuse", "openid-nonce-reuse")
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(reused.getResponse().getHeader("Location"));
        assertThat(callback.getHost()).isEqualTo("rp.example");
        assertThat(query(callback, "state")).isEqualTo("openid-reuse");
    }

    @Test
    void consent_approval_rejects_a_non_empty_subset_instead_of_changing_the_pending_decision() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage pending = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "subset-state", "subset-nonce");

        mockMvc.perform(post("/oauth2/authorize").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId())
                        .param("state", pending.serverState())
                        .param("scope", "openid"))
                .andExpect(status().isBadRequest());

        assertThat(consentScopes(fixture)).isEmpty();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id in (select id from oauth_authorization where registered_client_id = :id)")
                .param("id", fixture.internalClientId()).query(Long.class).single()).isZero();
    }

    @Test
    void current_identity_and_client_are_revalidated_after_consent_get_before_code_issue() throws Exception {
        Fixture locked = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession lockedSession = loginFor(locked);
        ConsentPage lockedPending = authorizeAuthenticated(
                locked, lockedSession, Set.of("openid", "profile"), "lock-at-consent", "lock-at-consent-nonce");
        String lockedAuthorizationId = pendingAuthorizationId(lockedPending.serverState());
        jdbcClient.sql("update accounts set locked_until = :until where id = :id")
                .param("until", Timestamp.from(BASE_TIME.plus(Duration.ofMinutes(10))))
                .param("id", locked.accountId()).update();

        DecisionResult lockedResult = concurrentApprove(lockedSession, locked,
                lockedPending.serverState(), new CyclicBarrier(1), "openid", "profile");

        assertThat(lockedResult.issuedCode()).isFalse();
        assertThat(lockedSession.isInvalid()).isTrue();
        assertThat(consentScopes(locked)).isEmpty();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", lockedAuthorizationId).query(Long.class).single()).isZero();

        Fixture disabledClient = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MockHttpSession clientSession = loginFor(disabledClient);
        ConsentPage clientPending = authorizeAuthenticated(
                disabledClient, clientSession, Set.of("openid"), "client-at-consent", "client-at-consent-nonce");
        String clientAuthorizationId = pendingAuthorizationId(clientPending.serverState());
        jdbcClient.sql("update oauth_client set status = 'DISABLED' where id = :id")
                .param("id", disabledClient.internalClientId()).update();

        DecisionResult disabledResult = concurrentApprove(clientSession, disabledClient,
                clientPending.serverState(), new CyclicBarrier(1), "openid");

        assertThat(disabledResult.issuedCode()).isFalse();
        assertThat(consentScopes(disabledClient)).isEmpty();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", clientAuthorizationId).query(Long.class).single()).isZero();
    }

    @Test
    void only_trusted_first_party_skips_consent() throws Exception {
        Fixture trusted = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid", "profile"));
        MockHttpSession session = new MockHttpSession();
        beginAuthorization(trusted, session, Set.of("openid", "profile"), "trusted-state", "trusted-nonce");
        LoginPage page = loginPage(session);
        MvcResult login = postLogin(session, page.flowId(), trusted.email(), PASSWORD)
                .andExpect(status().is3xxRedirection()).andReturn();
        MvcResult callback = mockMvc.perform(get(URI.create(login.getResponse().getHeader("Location"))).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI location = URI.create(callback.getResponse().getHeader("Location"));
        assertThat(location.getHost()).isEqualTo("rp.example");
        assertThat(query(location, "state")).isEqualTo("trusted-state");
    }

    @Test
    void tenant_mismatch_is_a_generic_login_denial_and_does_not_create_an_idp_principal() throws Exception {
        Fixture clientOwner = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        Fixture otherTenant = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MockHttpSession session = new MockHttpSession();
        beginAuthorization(clientOwner, session, Set.of("openid"), "tenant-state", "tenant-nonce");
        LoginPage page = loginPage(session);

        MvcResult result = postLogin(session, page.flowId(), otherTenant.email(), PASSWORD)
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("이메일 또는 비밀번호를 확인해 주세요");
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(jdbcClient.sql("select count(*) from oauth_subject where account_id = :id")
                .param("id", otherTenant.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void login_password_and_consent_posts_require_csrf_and_the_exact_issuer_origin() throws Exception {
        Fixture fixture = fixture(true, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession loginSession = new MockHttpSession();
        beginAuthorization(fixture, loginSession, Set.of("openid", "profile"), "security", "security-nonce");
        LoginPage loginPage = loginPage(loginSession);

        mockMvc.perform(post("/idp/login").session(loginSession)
                        .header("Origin", ISSUER)
                        .param("flowId", loginPage.flowId())
                        .param("email", fixture.email()).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/idp/login").session(loginSession).with(csrf())
                        .header("Origin", "http://evil.localhost:8080")
                        .param("flowId", loginPage.flowId())
                        .param("email", fixture.email()).param("password", PASSWORD))
                .andExpect(status().isForbidden());

        MvcResult loggedIn = postLogin(loginSession, loginPage.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection()).andReturn();
        MvcResult password = mockMvc.perform(get("/idp/password").session(loginSession))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/idp/password").session(loginSession).with(csrf())
                        .header("Origin", "https://idp.localhost:8080")
                        .param("flowId", hidden(password.getResponse().getContentAsString(), "flowId"))
                        .param("currentPassword", PASSWORD).param("newPassword", CHANGED_PASSWORD))
                .andExpect(status().isForbidden());

        MvcResult freshPassword = mockMvc.perform(get("/idp/password").session(loginSession))
                .andExpect(status().isOk()).andReturn();
        MvcResult changed = mockMvc.perform(post("/idp/password").session(loginSession).with(csrf())
                        .header("Origin", ISSUER)
                        .param("flowId", hidden(freshPassword.getResponse().getContentAsString(), "flowId"))
                        .param("currentPassword", PASSWORD).param("newPassword", CHANGED_PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn();
        ConsentPage consent = followToConsent(loginSession, changed.getResponse().getHeader("Location"));

        mockMvc.perform(post("/oauth2/authorize").session(loginSession).with(csrf())
                        .header("Origin", "http://rp.localhost:5173")
                        .param("client_id", fixture.clientId()).param("state", consent.serverState())
                        .param("scope", "openid", "profile"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/idp/consent/deny").session(loginSession)
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", consent.serverState()))
                .andExpect(status().isForbidden());
    }

    @Test
    void idle_and_absolute_session_boundaries_are_inclusive_and_clear_the_context() throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession idleSession = loginFor(fixture);

        clock.set(BASE_TIME.plus(Duration.ofMinutes(30)).minusNanos(1));
        mockMvc.perform(get("/idp/error").session(idleSession)).andExpect(status().isOk());
        assertThat(idleSession.isInvalid()).isFalse();

        clock.set(BASE_TIME);
        MockHttpSession exactIdle = loginFor(fixture);
        clock.set(BASE_TIME.plus(Duration.ofMinutes(30)));
        MvcResult idleExpired = mockMvc.perform(get("/idp/error").session(exactIdle))
                .andExpect(status().isOk()).andReturn();
        assertThat(exactIdle.isInvalid()).isTrue();
        assertExpiredCookie(idleExpired);

        clock.set(BASE_TIME);
        MockHttpSession absolute = loginFor(fixture);
        for (int minutes = 29; minutes < 8 * 60; minutes += 29) {
            clock.set(BASE_TIME.plus(Duration.ofMinutes(minutes)));
            mockMvc.perform(get("/idp/error").session(absolute)).andExpect(status().isOk());
        }
        clock.set(BASE_TIME.plus(Duration.ofHours(8)));
        MvcResult absoluteExpired = mockMvc.perform(get("/idp/error").session(absolute))
                .andExpect(status().isOk()).andReturn();
        assertThat(absolute.isInvalid()).isTrue();
        assertExpiredCookie(absoluteExpired);
    }

    @Test
    void an_account_locked_after_login_invalidates_the_idp_session_before_authorize() throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession session = loginFor(fixture);
        jdbcClient.sql("update accounts set locked_until = :until where id = :id")
                .param("until", Timestamp.from(BASE_TIME.plus(Duration.ofMinutes(10))))
                .param("id", fixture.accountId()).update();

        MvcResult result = performAuthorization(
                fixture, session, Set.of("openid"), "locked-after-login", "locked-nonce")
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(URI.create(result.getResponse().getHeader("Location")).getPath()).isEqualTo("/idp/login");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void must_change_password_set_after_login_blocks_authorize_with_the_existing_session() throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession session = loginFor(fixture);
        jdbcClient.sql("update accounts set must_change_password = true where id = :id")
                .param("id", fixture.accountId()).update();

        MvcResult result = performAuthorization(
                fixture, session, Set.of("openid"), "password-after-login", "password-nonce")
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/idp/password");
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void inactive_company_or_user_after_login_invalidates_before_authorize() throws Exception {
        Fixture companyFixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession companySession = loginFor(companyFixture);
        jdbcClient.sql("update companies set status = 'INACTIVE' where id = :id")
                .param("id", companyFixture.companyId()).update();
        assertInvalidatedBeforeAuthorize(companyFixture, companySession, "inactive-company");

        Fixture userFixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession userSession = loginFor(userFixture);
        jdbcClient.sql("update users set status = 'RESIGNED' where id = (select user_id from accounts where id = :id)")
                .param("id", userFixture.accountId()).update();
        assertInvalidatedBeforeAuthorize(userFixture, userSession, "inactive-user");
    }

    @Test
    void id_and_access_tokens_use_one_project_clock_instant_while_auth_time_stays_original()
            throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        MockHttpSession session = loginFor(fixture);
        for (int minutes : List.of(29, 58, 87, 116)) {
            clock.set(BASE_TIME.plus(Duration.ofMinutes(minutes)));
            mockMvc.perform(get("/idp/error").session(session)).andExpect(status().isOk());
        }
        clock.set(BASE_TIME.plus(Duration.ofHours(2)));

        MvcResult authorization = performAuthorization(
                fixture, session, Set.of("openid"), "later-sso", "later-sso-nonce")
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(authorization.getResponse().getHeader("Location"));
        String code = query(callback, "code");
        assertThat(code).isNotBlank();

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", code)
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk()).andReturn();
        var tokens = objectMapper.readTree(tokenResult.getResponse().getContentAsByteArray());
        var idClaims = jwtClaims(tokens.path("id_token").asText());
        var accessClaims = jwtClaims(tokens.path("access_token").asText());
        long issuedAt = BASE_TIME.plus(Duration.ofHours(2)).getEpochSecond();
        assertThat(idClaims.path("iat").asLong()).isEqualTo(issuedAt);
        assertThat(accessClaims.path("iat").asLong()).isEqualTo(issuedAt);
        assertThat(idClaims.path("exp").asLong()).isEqualTo(issuedAt + 300);
        assertThat(accessClaims.path("exp").asLong()).isEqualTo(issuedAt + 300);
        assertThat(idClaims.path("auth_time").asLong())
                .isEqualTo(BASE_TIME.getEpochSecond());
        assertThat(jdbcClient.sql("select authenticated_at from oauth_authorization where id = (select authorization_id from oauth_authorization_code where code_hash = :hash)")
                .param("hash", sha256(code)).query(Instant.class).single()).isEqualTo(BASE_TIME);
    }

    @Test
    void the_same_login_form_is_single_use_under_concurrency_and_a_stale_consent_state_cannot_replay()
            throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = new MockHttpSession();
        beginAuthorization(fixture, session, Set.of("openid", "profile"), "concurrent", "concurrent-nonce");
        String flowId = loginPage(session).flowId();
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(
                    executor.submit(() -> concurrentLogin(session, flowId, fixture, barrier)),
                    executor.submit(() -> concurrentLogin(session, flowId, fixture, barrier)));
            assertThat(results).extracting(result -> result.get(20, TimeUnit.SECONDS))
                    .containsExactlyInAnyOrder(302, 409);
        }

        String resumed = session.getAttribute("TEST_LAST_LOGIN_REDIRECT") instanceof String value
                ? value : pendingAuthorizationUri(fixture, Set.of("openid", "profile"), "concurrent", "concurrent-nonce");
        ConsentPage consent = followToConsent(session, resumed);
        URI callback = approve(session, fixture, consent.serverState(), "openid", "profile");
        assertCallback(callback, "concurrent");

        mockMvc.perform(post("/oauth2/authorize").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", consent.serverState())
                        .param("scope", "openid", "profile"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id in (select id from oauth_authorization where registered_client_id = :id)")
                .param("id", fixture.internalClientId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void concurrent_double_approval_of_one_pending_state_has_exactly_one_code_winner() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage pending = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "double-approve", "double-nonce");
        String authorizationId = pendingAuthorizationId(pending.serverState());
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<DecisionResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(
                    executor.submit(() -> concurrentApprove(
                            session, fixture, pending.serverState(), barrier, "openid", "profile")),
                    executor.submit(() -> concurrentApprove(
                            session, fixture, pending.serverState(), barrier, "openid", "profile")));
            results = calls.stream().map(call -> {
                try {
                    return call.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results.stream().filter(DecisionResult::issuedCode).count()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", authorizationId).query(Long.class).single()).isEqualTo(1L);
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile");
    }

    @Test
    void concurrent_approval_and_denial_of_one_pending_state_have_exactly_one_winner() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage pending = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "approve-deny", "approve-deny-nonce");
        String authorizationId = pendingAuthorizationId(pending.serverState());
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<DecisionResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(
                    executor.submit(() -> concurrentApprove(
                            session, fixture, pending.serverState(), barrier, "openid", "profile")),
                    executor.submit(() -> concurrentDeny(
                            session, fixture, pending.serverState(), barrier)));
            results = calls.stream().map(call -> {
                try {
                    return call.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        long approvals = results.stream().filter(DecisionResult::issuedCode).count();
        long denials = results.stream().filter(DecisionResult::deniedAccess).count();
        assertThat(approvals + denials).as("decision responses %s", results).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", authorizationId).query(Long.class).single()).isEqualTo(approvals);
        if (approvals == 1) {
            assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile");
        } else {
            assertThat(consentScopes(fixture)).isEmpty();
        }
    }

    @Test
    void a_code_save_failure_rolls_back_the_consent_snapshot_and_leaves_pending_retryable() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage pending = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "rollback-consent", "rollback-nonce");
        String authorizationId = pendingAuthorizationId(pending.serverState());
        AtomicBoolean failCodeSave = new AtomicBoolean(true);
        doAnswer(invocation -> {
            com.sweet.authstudy.oauth.domain.OAuthAuthorization authorization = invocation.getArgument(0);
            if (authorization.authorizationCode().isPresent() && failCodeSave.getAndSet(false)) {
                throw new IllegalStateException("simulated code save failure");
            }
            return invocation.callRealMethod();
        }).when(authorizationRepository).save(any());

        DecisionResult failed = concurrentApprove(
                session, fixture, pending.serverState(), new CyclicBarrier(1), "openid", "profile");

        assertThat(failed.issuedCode()).isFalse();
        assertThat(consentScopes(fixture)).isEmpty();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", authorizationId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization where id = :id and server_state_hash = :hash")
                .param("id", authorizationId).param("hash", sha256(pending.serverState()))
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void concurrent_incremental_approvals_do_not_lose_scope_updates() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile", "email"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage profile = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "profile-decision", "profile-nonce");
        ConsentPage email = authorizeAuthenticated(
                fixture, session, Set.of("openid", "email"), "email-decision", "email-nonce");
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<DecisionResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(
                    executor.submit(() -> concurrentApprove(
                            session, fixture, profile.serverState(), barrier, "openid", "profile")),
                    executor.submit(() -> concurrentApprove(
                            session, fixture, email.serverState(), barrier, "openid", "email")));
            results = calls.stream().map(call -> {
                try {
                    return call.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results).allMatch(DecisionResult::issuedCode);
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void a_stale_review_snapshot_does_not_reject_or_lose_a_concurrent_incremental_approval()
            throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of(
                "openid", "profile", "email", "hr.company", "hr.roles"));
        MockHttpSession session = loginFor(fixture);
        ConsentPage baseline = authorizeAuthenticated(
                fixture, session, Set.of("openid"), "baseline", "baseline-nonce");
        assertCallback(approve(session, fixture, baseline.serverState(), "openid"), "baseline");

        ConsentPage slowProfile = authorizeAuthenticated(
                fixture, session, Set.of("openid", "profile"), "slow-profile", "slow-profile-nonce");
        ConsentPage fastEmail = authorizeAuthenticated(
                fixture, session, Set.of("openid", "email"), "fast-email", "fast-email-nonce");
        String slowAuthorizationId = pendingAuthorizationId(slowProfile.serverState());
        String fastAuthorizationId = pendingAuthorizationId(fastEmail.serverState());
        CountDownLatch slowReviewCaptured = new CountDownLatch(1);
        CountDownLatch releaseSlowReview = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object decision = invocation.callRealMethod();
            if (slowProfile.serverState().equals(invocation.getArgument(0))) {
                slowReviewCaptured.countDown();
                if (!releaseSlowReview.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("slow consent review was not released");
                }
            }
            return decision;
        }).when(consentService).validateApproval(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(UUID.class));

        DecisionResult slowResult;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var slowCall = executor.submit(() -> concurrentApprove(
                    session, fixture, slowProfile.serverState(), new CyclicBarrier(1), "openid", "profile"));
            assertThat(slowReviewCaptured.await(10, TimeUnit.SECONDS)).isTrue();
            assertCallback(approve(session, fixture, fastEmail.serverState(), "openid", "email"), "fast-email");
            releaseSlowReview.countDown();
            slowResult = slowCall.get(20, TimeUnit.SECONDS);
        } finally {
            releaseSlowReview.countDown();
        }

        assertThat(slowResult.issuedCode()).isTrue();
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", slowAuthorizationId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", fastAuthorizationId).query(Long.class).single()).isEqualTo(1L);
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email")
                .doesNotContain("hr.roles");

        ConsentPage spoof = authorizeAuthenticated(fixture, session, Set.of("openid", "hr.company"),
                "spoof-extra", "spoof-extra-nonce");
        String spoofAuthorizationId = pendingAuthorizationId(spoof.serverState());
        mockMvc.perform(post("/oauth2/authorize").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", spoof.serverState())
                        .param("scope", "openid", "hr.company", "hr.roles"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization_code where authorization_id = :id")
                .param("id", spoofAuthorizationId).query(Long.class).single()).isZero();
        assertThat(consentScopes(fixture)).containsExactlyInAnyOrder("openid", "profile", "email")
                .doesNotContain("hr.company", "hr.roles");
    }

    @Test
    void pre_login_session_preserves_only_the_authorization_allowlist_and_never_raw_secrets() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", "allowlist-state")
                        .queryParam("nonce", "allowlist-nonce")
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_verifier", VERIFIER)
                        .queryParam("access_token", "raw-access-token")
                        .queryParam("client_secret", "raw-client-secret"))
                .andExpect(status().is3xxRedirection());

        assertThat(sessionValues(session)).doesNotContain(
                VERIFIER, "raw-access-token", "raw-client-secret", "code_verifier", "access_token", "client_secret");
    }

    @Test
    void oauth_request_without_state_or_openid_nonce_is_safely_resumed_after_login() throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid", "profile"));
        MockHttpSession session = new MockHttpSession();

        MvcResult begin = mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "profile")
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(URI.create(begin.getResponse().getHeader("Location")).getPath()).isEqualTo("/idp/login");

        LoginPage page = loginPage(session);
        MvcResult login = postLogin(session, page.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection()).andReturn();
        URI resumed = URI.create(login.getResponse().getHeader("Location"));
        assertThat(query(resumed, "state")).isNull();
        assertThat(query(resumed, "nonce")).isNull();
        assertThat(query(resumed, "scope")).isEqualTo("profile");

        MvcResult callback = mockMvc.perform(get(resumed).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI location = URI.create(callback.getResponse().getHeader("Location"));
        assertThat(location.getHost()).isEqualTo("rp.example");
        assertThat(query(location, "state")).isNull();
        assertThat(query(location, "code")).isNotBlank();
    }

    @Test
    void malformed_registered_request_clears_an_older_pending_request_and_login_form() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MockHttpSession session = new MockHttpSession();
        beginAuthorization(fixture, session, Set.of("openid"), "older-state", "older-nonce");
        String olderFlow = loginPage(session).flowId();

        MvcResult malformed = mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", "malformed-state")
                        .queryParam("nonce", "malformed-nonce"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(query(URI.create(malformed.getResponse().getHeader("Location")), "error"))
                .isEqualTo("invalid_request");

        postLogin(session, olderFlow, fixture.email(), PASSWORD)
                .andExpect(status().isConflict());
        assertThat(session.getAttribute(IdpLoginController.PENDING_AUTHORIZATION_ATTRIBUTE)).isNull();
    }

    @Test
    void opaque_state_nonce_and_encoded_registered_redirect_round_trip_exactly_once_on_resume() throws Exception {
        Fixture fixture = fixture(false, "TRUSTED_FIRST_PARTY", Set.of("openid"));
        URI encodedRedirect = URI.create(
                "https://rp.example/cb%2Fsegment?existing=a%2Bb&literal=%252F");
        registerRedirect(fixture, encodedRedirect);
        String opaqueState = "state+%2F& value";
        String opaqueNonce = "nonce+%2F& value";
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", encodedRedirect.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", opaqueState)
                        .queryParam("nonce", opaqueNonce)
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection());

        LoginPage page = loginPage(session);
        MvcResult login = postLogin(session, page.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection()).andReturn();
        URI resumed = URI.create(login.getResponse().getHeader("Location"));
        assertThat(query(resumed, "state")).isEqualTo(opaqueState);
        assertThat(query(resumed, "nonce")).isEqualTo(opaqueNonce);
        assertThat(query(resumed, "redirect_uri")).isEqualTo(encodedRedirect.toString());
        assertThat(resumed.getRawQuery()).contains("state=state%2B%252F%26%20value");

        MvcResult callbackResult = mockMvc.perform(get(resumed).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(callbackResult.getResponse().getHeader("Location"));
        assertThat(callback.toString()).startsWith(encodedRedirect.toString() + "&");
        assertThat(query(callback, "state")).isEqualTo(opaqueState);
    }

    @Test
    void access_denied_preserves_encoded_registered_redirect_and_opaque_state_exactly_once() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        URI encodedRedirect = URI.create(
                "https://rp.example/deny%2Fcallback?existing=a%2Bb&literal=%252F");
        registerRedirect(fixture, encodedRedirect);
        String opaqueState = "deny+%2F& value";
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/oauth2/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", encodedRedirect.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", opaqueState)
                        .queryParam("nonce", "deny-nonce")
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection());
        LoginPage page = loginPage(session);
        MvcResult login = postLogin(session, page.flowId(), fixture.email(), PASSWORD)
                .andExpect(status().is3xxRedirection()).andReturn();
        ConsentPage consent = followToConsent(session, login.getResponse().getHeader("Location"));

        MvcResult denied = mockMvc.perform(post("/idp/consent/deny").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId())
                        .param("state", consent.serverState()))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(denied.getResponse().getHeader("Location"));
        assertThat(callback.toString()).startsWith(encodedRedirect.toString() + "&error=access_denied");
        assertThat(query(callback, "state")).isEqualTo(opaqueState);
    }

    @Test
    void an_unregistered_redirect_is_rejected_locally_before_credentials_are_requested() throws Exception {
        Fixture fixture = fixture(false, "CONSENT_REQUIRED", Set.of("openid"));
        MvcResult result = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", "https://evil.example/callback")
                        .queryParam("scope", "openid")
                        .queryParam("state", "fixation-state")
                        .queryParam("nonce", "fixation-nonce")
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getHeader("Location")).isNull();
    }

    @Test
    void a_login_form_is_bound_to_the_pending_authorization_snapshot_and_rejects_request_replacement()
            throws Exception {
        Fixture original = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        Fixture replacement = fixture(false, "CONSENT_REQUIRED", Set.of("openid", "profile"));
        MockHttpSession session = new MockHttpSession();
        beginAuthorization(original, session, Set.of("openid", "profile"), "original-state", "original-nonce");
        String originalFlow = loginPage(session).flowId();

        beginAuthorization(replacement, session, Set.of("openid", "profile"),
                "replacement-state", "replacement-nonce");

        postLogin(session, originalFlow, original.email(), PASSWORD)
                .andExpect(status().isConflict());
        assertThat(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    private int concurrentLogin(MockHttpSession session, String flowId, Fixture fixture, CyclicBarrier barrier)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        MvcResult result = postLogin(session, flowId, fixture.email(), PASSWORD).andReturn();
        if (result.getResponse().getStatus() == 302) {
            session.setAttribute("TEST_LAST_LOGIN_REDIRECT", result.getResponse().getHeader("Location"));
        }
        return result.getResponse().getStatus();
    }

    private DecisionResult concurrentApprove(MockHttpSession session, Fixture fixture,
            String state, CyclicBarrier barrier, String... scopes) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/oauth2/authorize").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", state)
                        .param("scope", scopes)).andReturn();
        String location = result.getResponse().getHeader("Location");
        return new DecisionResult(result.getResponse().getStatus(), location);
    }

    private DecisionResult concurrentDeny(MockHttpSession session, Fixture fixture,
            String state, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/idp/consent/deny").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", state)).andReturn();
        return new DecisionResult(result.getResponse().getStatus(),
                result.getResponse().getHeader("Location"));
    }

    private void assertInvalidatedBeforeAuthorize(Fixture fixture, MockHttpSession session, String state)
            throws Exception {
        MvcResult result = performAuthorization(
                fixture, session, Set.of("openid"), state, state + "-nonce")
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(URI.create(result.getResponse().getHeader("Location")).getPath()).isEqualTo("/idp/login");
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession loginFor(Fixture fixture) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult page = mockMvc.perform(get("/idp/login").session(session))
                .andExpect(status().isOk()).andReturn();
        String flowId = hidden(page.getResponse().getContentAsString(), "flowId");
        postLogin(session, flowId, fixture.email(), PASSWORD).andExpect(status().is3xxRedirection());
        return session;
    }

    private LoginPage loginPage(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/idp/login").session(session))
                .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("<label", "name=\"email\"", "name=\"password\"");
        return new LoginPage(hidden(html, "flowId"), html);
    }

    private org.springframework.test.web.servlet.ResultActions postLogin(
            MockHttpSession session, String flowId, String email, String password) throws Exception {
        return mockMvc.perform(post("/idp/login").session(session).with(csrf())
                .header("Origin", ISSUER)
                .param("flowId", flowId).param("email", email).param("password", password));
    }

    private String beginAuthorization(Fixture fixture, MockHttpSession session, Set<String> scopes,
            String state, String nonce) throws Exception {
        MvcResult result = performAuthorization(fixture, session, scopes, state, nonce)
                .andExpect(status().is3xxRedirection()).andReturn();
        return result.getResponse().getHeader("Location");
    }

    private org.springframework.test.web.servlet.ResultActions performAuthorization(
            Fixture fixture, MockHttpSession session, Set<String> scopes, String state, String nonce)
            throws Exception {
        return mockMvc.perform(get("/oauth2/authorize").session(session)
                .queryParam("response_type", "code")
                .queryParam("client_id", fixture.clientId())
                .queryParam("redirect_uri", CALLBACK.toString())
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"));
    }

    private ConsentPage authorizeAuthenticated(Fixture fixture, MockHttpSession session,
            Set<String> scopes, String state, String nonce) throws Exception {
        MvcResult result = performAuthorization(fixture, session, scopes, state, nonce)
                .andExpect(status().is3xxRedirection()).andReturn();
        return consentPage(session, result.getResponse().getHeader("Location"));
    }

    private ConsentPage followToConsent(MockHttpSession session, String authorizationUri) throws Exception {
        MvcResult result = mockMvc.perform(get(URI.create(authorizationUri)).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        return consentPage(session, result.getResponse().getHeader("Location"));
    }

    private ConsentPage consentPage(MockHttpSession session, String location) throws Exception {
        assertThat(URI.create(location).getPath()).isEqualTo("/idp/consent");
        MvcResult result = mockMvc.perform(get(URI.create(location)).session(session))
                .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        return new ConsentPage(hidden(html, "state"), html);
    }

    private URI approve(MockHttpSession session, Fixture fixture, String state, String... scopes) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/authorize").session(session).with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId()).param("state", state)
                        .param("scope", scopes))
                .andExpect(status().is3xxRedirection()).andReturn();
        return URI.create(result.getResponse().getHeader("Location"));
    }

    private void assertCallback(URI callback, String expectedState) {
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath())
                .isEqualTo("https://rp.example/callback");
        assertThat(query(callback, "source")).isEqualTo("idp");
        assertThat(query(callback, "state")).isEqualTo(expectedState);
        assertThat(query(callback, "code")).isNotBlank();
        assertThat(query(callback, "error")).isNull();
    }

    private void assertCookieIsLocalDevelopmentSession(MvcResult result) {
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("IDP_AUTH_SESSION=")
                .contains("Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure");
    }

    private void assertExpiredCookie(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("IDP_AUTH_SESSION=").contains("Max-Age=0"));
    }

    private Authentication sessionAuthentication(MockHttpSession session) {
        Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(value).isInstanceOf(SecurityContext.class);
        return ((SecurityContext) value).getAuthentication();
    }

    private String sessionValues(HttpSession session) {
        StringBuilder values = new StringBuilder();
        session.getAttributeNames().asIterator().forEachRemaining(name -> {
            Object value = session.getAttribute(name);
            values.append(name).append('=').append(value).append(';');
        });
        return values.toString();
    }

    private List<String> consentScopes(Fixture fixture) {
        return jdbcClient.sql("""
                        select scope from oauth_consent_scope
                         where consent_id = (
                               select id from oauth_consent
                                where principal_account_id = :accountId
                                  and registered_client_id = :clientId)
                         order by scope
                        """).param("accountId", fixture.accountId())
                .param("clientId", fixture.internalClientId()).query(String.class).list();
    }

    private String pendingAuthorizationId(String rawState) {
        return jdbcClient.sql("select id from oauth_authorization where server_state_hash = :hash")
                .param("hash", sha256(rawState)).query(String.class).single();
    }

    private void registerRedirect(Fixture fixture, URI redirect) {
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'AUTHORIZATION')
                        """).param("clientId", fixture.internalClientId())
                .param("redirectUri", redirect.toString()).update();
    }

    private String pendingAuthorizationUri(Fixture fixture, Set<String> scopes, String state, String nonce) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", fixture.clientId())
                .queryParam("redirect_uri", CALLBACK.toString())
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("state", state).queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256").build().encode().toUriString();
    }

    private Fixture fixture(boolean mustChangePassword, String trust, Set<String> scopes) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String companyCode = "BROWSER_" + suffix.toUpperCase();
        String domain = "browser-" + suffix + ".example";
        String email = "user@" + domain;
        Instant now = clock.instant();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", companyCode).param("domain", domain)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                        """).param("companyId", companyId).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'Browser User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, :status, :now, :now) returning id
                        """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 21)).param("positionId", positionId)
                .param("status", "ACTIVE")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, :passwordHash, 'ACTIVE', :mustChange, :now, :now)
                        returning id
                        """).param("companyId", companyId).param("userId", userId).param("email", email)
                .param("passwordHash", passwordEncoder.encode(PASSWORD)).param("mustChange", mustChangePassword)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:accountId, 'USER')")
                .param("accountId", accountId).update();

        String clientId = "browser-client-" + suffix;
        String displayName = "브라우저 테스트 앱 " + suffix;
        long internalClientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, :displayName, 'ACTIVE', :trust,
                                true, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", clientId)
                .param("displayName", displayName).param("trust", trust)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'AUTHORIZATION')
                        """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        scopes.forEach(scope -> jdbcClient.sql(
                        "insert into oauth_client_scope(client_id, scope) values (:clientId, :scope)")
                .param("clientId", internalClientId).param("scope", scope).update());
        return new Fixture(companyId, accountId, internalClientId, clientId, displayName, email);
    }

    private String hidden(String html, String name) {
        var matcher = Pattern.compile("name=\\\"" + Pattern.quote(name)
                + "\\\"[^>]*value=\\\"([^\\\"]+)\\\"").matcher(html);
        assertThat(matcher.find()).as("hidden input %s", name).isTrue();
        return matcher.group(1);
    }

    private String query(URI uri, String name) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (org.springframework.web.util.UriUtils.decode(rawName, StandardCharsets.UTF_8)
                    .equals(name)) {
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                return org.springframework.web.util.UriUtils.decode(rawValue, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private com.fasterxml.jackson.databind.JsonNode jwtClaims(String token) throws Exception {
        return objectMapper.readTree(new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(long companyId, long accountId, long internalClientId,
            String clientId, String clientDisplayName, String email) { }
    private record LoginPage(String flowId, String html) { }
    private record ConsentPage(String serverState, String html) { }
    private record DecisionResult(int status, String location) {
        boolean issuedCode() {
            return status == 302 && location != null && queryValue(location, "code") != null;
        }

        boolean deniedAccess() {
            return status == 302 && location != null
                    && "access_denied".equals(queryValue(location, "error"));
        }

        private static String queryValue(String location, String name) {
            URI uri = URI.create(location);
            if (uri.getRawQuery() == null) return null;
            for (String pair : uri.getRawQuery().split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                if (org.springframework.web.util.UriUtils.decode(key, StandardCharsets.UTF_8).equals(name)) {
                    return separator < 0 ? "" : org.springframework.web.util.UriUtils.decode(
                            pair.substring(separator + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME, ZoneId.of("UTC"));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
