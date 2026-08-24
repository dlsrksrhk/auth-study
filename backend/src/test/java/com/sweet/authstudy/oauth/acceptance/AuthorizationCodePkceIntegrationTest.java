package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.oauth.infrastructure.SpringOAuth2AuthorizationService;
import com.sweet.authstudy.oauth.presentation.IdpLoginController;
import com.sweet.authstudy.oauth.presentation.IdpSessionAuthentication;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AuthorizationCodePkceIntegrationTest {

    private static final String ISSUER = "http://idp.localhost:8080";
    private static final URI CALLBACK = URI.create("https://rp.example/callback?source=idp");
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String WRONG_VERIFIER =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private SpringOAuth2AuthorizationService authorizationService;

    @Test
    void discovery_driven_authorization_code_exchange_succeeds_exactly_once() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        assertThat(Duration.between(
                jdbcInstant("select issued_at from oauth_authorization_code where authorization_id = :id", issued.authorizationId()),
                jdbcInstant("select expires_at from oauth_authorization_code where authorization_id = :id", issued.authorizationId())))
                .isEqualTo(Duration.ofSeconds(60));

        MvcResult tokenResult = mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();
        JsonNode tokens = objectMapper.readTree(tokenResult.getResponse().getContentAsByteArray());
        String accessToken = tokens.path("access_token").asText();
        assertThat(jdbcClient.sql("select code_hash from oauth_authorization_code where authorization_id = :id")
                .param("id", issued.authorizationId()).query(String.class).single())
                .isEqualTo(sha256(issued.code())).isNotEqualTo(issued.code());
        assertThat(jdbcClient.sql("select code_challenge from oauth_authorization_code where authorization_id = :id")
                .param("id", issued.authorizationId()).query(String.class).single()).isNotEqualTo(VERIFIER);
        assertThat(jdbcClient.sql("select access_token_hash from oauth_access_token where authorization_id = :id")
                .param("id", issued.authorizationId()).query(String.class).single())
                .isEqualTo(sha256(accessToken)).isNotEqualTo(accessToken);
        String persistedAuthorization = jdbcClient.sql(
                        "select attributes::text from oauth_authorization where id = :id")
                .param("id", issued.authorizationId()).query(String.class).single();
        assertThat(persistedAuthorization).doesNotContain(
                issued.code(), accessToken, VERIFIER);

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
    }

    @Test
    void confidential_basic_client_uses_the_project_service_and_custom_atomic_provider() throws Exception {
        String rawSecret = "confidential-secret-" + UUID.randomUUID();
        Fixture fixture = fixture(false, rawSecret);
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        MvcResult tokenResult = mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        JsonNode tokens = objectMapper.readTree(tokenResult.getResponse().getContentAsByteArray());
        String refreshToken = tokens.path("refresh_token").asText();

        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isEqualTo(1L);
        assertThat(jdbcClient.sql("select secret_hash from oauth_client_secret where client_id = :id")
                .param("id", fixture.internalClientId()).query(String.class).single())
                .startsWith("$2").doesNotContain(rawSecret);
        assertThat(jdbcClient.sql("select refresh_token_hash from oauth_refresh_token where authorization_id = :id")
                .param("id", issued.authorizationId()).query(String.class).single())
                .isEqualTo(sha256(refreshToken)).isNotEqualTo(refreshToken);
    }

    @Test
    void consent_required_pending_authorization_round_trips_through_the_project_consent_screen() throws Exception {
        Fixture fixture = fixture(true);
        Endpoints endpoints = discovery();
        String rpState = "rp-state-" + UUID.randomUUID();
        String nonce = "nonce-" + UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        Instant authenticatedAt = Instant.now();
        IdpSessionAuthentication idp = new IdpSessionAuthentication(
                fixture.accountId(), fixture.companyId(), fixture.userId(), Set.of(AccountRole.USER),
                fixture.subject(), authenticatedAt);
        session.setAttribute(IdpLoginController.LAST_ACCESS_ATTRIBUTE, authenticatedAt);

        MvcResult pendingResult = mockMvc.perform(get(endpoints.authorizationPath())
                        .session(session)
                        .with(authentication(idp))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid profile")
                        .queryParam("state", rpState)
                        .queryParam("nonce", nonce)
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MvcResult consentResult = mockMvc.perform(
                        get(URI.create(pendingResult.getResponse().getHeader("Location"))).session(session))
                .andExpect(status().isOk()).andReturn();
        String consentPage = consentResult.getResponse().getContentAsString();
        assertThat(consentPage).contains("접근 권한 동의", "action=\"/oauth2/authorize\"");
        var stateMatcher = Pattern.compile("name=\"state\" value=\"([^\"]+)\"").matcher(consentPage);
        assertThat(stateMatcher.find()).isTrue();
        String consentState = stateMatcher.group(1);
        assertThat(consentState).isNotBlank().isNotEqualTo(rpState);
        assertThat(jdbcClient.sql("""
                        select count(*) from oauth_authorization
                        where server_state_hash = :stateHash
                          and attributes -> 'authorizationRequest' ->> 'rpState' = :rpState
                          and attributes -> 'authorizationRequest' ->> 'nonce' = :nonce
                        """).param("stateHash", sha256(consentState)).param("rpState", rpState)
                .param("nonce", nonce).query(Long.class).single()).isEqualTo(1L);

        MvcResult approved = mockMvc.perform(post(endpoints.authorizationPath())
                        .session(session)
                        .with(csrf())
                        .header("Origin", ISSUER)
                        .param("client_id", fixture.clientId())
                        .param("state", consentState)
                        .param("scope", "openid", "profile"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI callback = URI.create(approved.getResponse().getHeader("Location"));
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath())
                .isEqualTo("https://rp.example/callback");
        assertThat(query(callback, "state")).isEqualTo(rpState);
        String code = query(callback, "code");
        assertThat(code).isNotBlank();

        mockMvc.perform(tokenRequest(endpoints, fixture, code, VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    @Test
    void missing_verifier_consumes_the_code_and_returns_invalid_grant() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        mockMvc.perform(post(endpoints.tokenPath())
                        .header("Origin", ISSUER)
                        .param("grant_type", "authorization_code")
                        .param("client_id", fixture.clientId())
                        .param("code", issued.code())
                        .param("redirect_uri", CALLBACK.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void plain_pkce_method_is_rejected_as_invalid_request_before_a_code_is_issued() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();

        MvcResult result = mockMvc.perform(get(endpoints.authorizationPath())
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", "plain-state")
                        .queryParam("nonce", "plain-nonce")
                        .queryParam("code_challenge", VERIFIER)
                        .queryParam("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        URI location = URI.create(result.getResponse().getHeader("Location"));
        assertThat(query(location, "error")).isEqualTo("invalid_request");
        assertThat(query(location, "state")).isEqualTo("plain-state");
        assertThat(jdbcClient.sql("select count(*) from oauth_authorization where registered_client_id = :id")
                .param("id", fixture.internalClientId()).query(Long.class).single()).isZero();
    }

    @Test
    void wrong_verifier_consumes_the_code_and_replay_with_the_right_verifier_stays_invalid_grant()
            throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), WRONG_VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void an_expired_code_is_consumed_and_returns_invalid_grant() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");
        jdbcClient.sql("update oauth_authorization_code set expires_at = issued_at + interval '1 millisecond' where authorization_id = :id")
                .param("id", issued.authorizationId()).update();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
    }

    @Test
    void two_concurrent_http_exchanges_cannot_both_succeed() throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> exchangeAfterBarrier(start, endpoints, fixture, issued)),
                    executor.submit(() -> exchangeAfterBarrier(start, endpoints, fixture, issued)));
            List<ExchangeResult> results = futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(results).extracting(ExchangeResult::status)
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(results.stream().filter(result -> result.status() == 400)
                    .map(ExchangeResult::error).toList()).containsExactly("invalid_grant");
        }
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(jdbcClient.sql("select count(*) from oauth_access_token where authorization_id = :id")
                .param("id", issued.authorizationId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void completed_authorization_revocation_while_exchange_waits_on_the_code_lock_prevents_token_issue()
            throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedMutationWhileCodeLocked(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update oauth_authorization
                           set status = 'REVOKED', revocation_reason = 'RACE_REVOKED', revoked_at = now()
                         where id = :id
                        """).param("id", issued.authorizationId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void completed_client_disable_while_exchange_waits_on_the_code_lock_prevents_token_issue()
            throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedMutationWhileCodeLocked(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update oauth_client set status = 'DISABLED', updated_at = now()
                         where id = :id
                        """).param("id", fixture.internalClientId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(401, "invalid_client"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void completed_revocation_after_validation_but_before_final_save_returns_invalid_grant_without_tokens()
            throws Exception {
        Fixture fixture = fixture(false, "post-validation-revoke-" + UUID.randomUUID());
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedPostValidationMutation(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update oauth_authorization
                           set status = 'REVOKED', revocation_reason = 'POST_VALIDATION_REVOKE', revoked_at = now()
                         where id = :id
                        """).param("id", issued.authorizationId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
        assertThat(jdbcClient.sql("select status from oauth_authorization where id = :id")
                .param("id", issued.authorizationId()).query(String.class).single()).isEqualTo("REVOKED");
    }

    @Test
    void completed_client_disable_after_validation_but_before_final_save_returns_invalid_grant_without_tokens()
            throws Exception {
        Fixture fixture = fixture(false, "post-validation-client-" + UUID.randomUUID());
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedPostValidationMutation(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update oauth_client set status = 'DISABLED', updated_at = now()
                         where id = :id
                        """).param("id", fixture.internalClientId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void completed_account_disable_after_validation_but_before_final_save_returns_invalid_grant_without_tokens()
            throws Exception {
        Fixture fixture = fixture(false, "post-validation-account-" + UUID.randomUUID());
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedPostValidationMutation(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update accounts set status = 'DISABLED', updated_at = now()
                         where id = :id
                        """).param("id", fixture.accountId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void completed_user_resignation_after_validation_but_before_final_save_returns_invalid_grant_without_tokens()
            throws Exception {
        Fixture fixture = fixture(false, "post-validation-user-" + UUID.randomUUID());
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedPostValidationMutation(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update users set status = 'RESIGNED', updated_at = now()
                         where id = (select user_id from accounts where id = :accountId)
                        """).param("accountId", fixture.accountId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void completed_company_disable_after_validation_but_before_final_save_returns_invalid_grant_without_tokens()
            throws Exception {
        Fixture fixture = fixture(false, "post-validation-company-" + UUID.randomUUID());
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");

        ExchangeResult result = exchangeAfterCommittedPostValidationMutation(
                endpoints, fixture, issued, () -> jdbcClient.sql("""
                        update companies set status = 'INACTIVE', updated_at = now()
                         where id = (select company_id from accounts where id = :accountId)
                        """).param("accountId", fixture.accountId()).update());

        assertThat(result).isEqualTo(new ExchangeResult(400, "invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
    }

    @Test
    void an_account_disabled_before_atomic_validation_returns_invalid_grant_and_consumes_the_code()
            throws Exception {
        Fixture fixture = fixture();
        Endpoints endpoints = discovery();
        IssuedCode issued = authorize(endpoints, fixture, VERIFIER, "S256");
        jdbcClient.sql("update accounts set status = 'DISABLED', updated_at = now() where id = :id")
                .param("id", fixture.accountId()).update();

        mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(codeUsedAt(issued.authorizationId())).isNotNull();
        assertThat(accessTokenCount(issued.authorizationId())).isZero();
        assertThat(refreshTokenCount(issued.authorizationId())).isZero();
    }

    private ExchangeResult exchangeAfterCommittedPostValidationMutation(
            Endpoints endpoints, Fixture fixture, IssuedCode issued, Runnable mutation) throws Exception {
        CountDownLatch finalSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            var candidate = invocation.getArgument(
                    0, org.springframework.security.oauth2.server.authorization.OAuth2Authorization.class);
            if (candidate.getAccessToken() != null) {
                finalSaveEntered.countDown();
                await(releaseFinalSave);
            }
            return invocation.callRealMethod();
        }).when(authorizationService).save(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var exchange = executor.submit(() ->
                    exchangeAfterBarrier(new CyclicBarrier(1), endpoints, fixture, issued));
            assertThat(finalSaveEntered.await(20, TimeUnit.SECONDS)).isTrue();
            mutation.run();
            releaseFinalSave.countDown();
            return exchange.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFinalSave.countDown();
            doCallRealMethod().when(authorizationService).save(any());
        }
    }

    private ExchangeResult exchangeAfterCommittedMutationWhileCodeLocked(
            Endpoints endpoints, Fixture fixture, IssuedCode issued, Runnable mutation) throws Exception {
        CountDownLatch codeLocked = new CountDownLatch(1);
        CountDownLatch releaseCode = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var locker = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbcClient.sql("select id from oauth_authorization_code where code_hash = :hash for update")
                        .param("hash", sha256(issued.code())).query(Long.class).single();
                codeLocked.countDown();
                await(releaseCode);
            }));
            assertThat(codeLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var exchange = executor.submit(() ->
                    exchangeAfterBarrier(new CyclicBarrier(1), endpoints, fixture, issued));
            awaitBlockedCodeExchange();

            mutation.run();
            releaseCode.countDown();
            locker.get(20, TimeUnit.SECONDS);
            return exchange.get(20, TimeUnit.SECONDS);
        } finally {
            releaseCode.countDown();
        }
    }

    private void awaitBlockedCodeExchange() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long blocked = jdbcClient.sql("""
                    select count(*) from pg_stat_activity
                     where pid <> pg_backend_pid()
                       and datname = current_database()
                       and state = 'active'
                       and wait_event_type = 'Lock'
                       and query ilike '%oauth_authorization_code%'
                    """).query(Long.class).single();
            if (blocked > 0) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Authorization-code exchange did not block on the code row lock.");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the authorization-code row lock.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private ExchangeResult exchangeAfterBarrier(CyclicBarrier start, Endpoints endpoints,
            Fixture fixture, IssuedCode issued) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(tokenRequest(endpoints, fixture, issued.code(), VERIFIER)).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new ExchangeResult(result.getResponse().getStatus(),
                json.path("error").isMissingNode() ? null : json.path("error").asText());
    }

    private IssuedCode authorize(Endpoints endpoints, Fixture fixture, String verifier, String method)
            throws Exception {
        String state = "state-" + UUID.randomUUID();
        String nonce = "nonce-" + UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(get(endpoints.authorizationPath())
                        .session(session)
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", state)
                        .queryParam("nonce", nonce)
                        .queryParam("code_challenge", challenge(verifier))
                        .queryParam("code_challenge_method", method))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI location = URI.create(result.getResponse().getHeader("Location"));
        assertThat(location.getScheme() + "://" + location.getAuthority() + location.getPath())
                .isEqualTo("https://rp.example/callback");
        assertThat(query(location, "state")).isEqualTo(state);
        assertThat(query(location, "error")).isNull();
        String code = query(location, "code");
        assertThat(code).isNotBlank();
        String authorizationId = jdbcClient.sql("""
                        select authorization_id from oauth_authorization_code where code_hash = :hash
                        """).param("hash", sha256(code)).query(String.class).single();
        return new IssuedCode(code, authorizationId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
            Endpoints endpoints, Fixture fixture, String code, String verifier) {
        var request = post(endpoints.tokenPath())
                .header("Origin", ISSUER)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", CALLBACK.toString())
                .param("code_verifier", verifier);
        return fixture.rawSecret() == null
                ? request.param("client_id", fixture.clientId())
                : request.with(httpBasic(fixture.clientId(), fixture.rawSecret()));
    }

    private Endpoints discovery() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode discovery = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        URI authorizationEndpoint = URI.create(discovery.path("authorization_endpoint").asText());
        URI tokenEndpoint = URI.create(discovery.path("token_endpoint").asText());
        assertThat(discovery.path("issuer").asText()).isEqualTo(ISSUER);
        return new Endpoints(authorizationEndpoint.getPath(), tokenEndpoint.getPath());
    }

    private Fixture fixture() {
        return fixture(false, null);
    }

    private Fixture fixture(boolean consentRequired) {
        return fixture(consentRequired, null);
    }

    private Fixture fixture(boolean consentRequired, String rawSecret) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "PKCE_" + suffix.toUpperCase();
        Instant now = Instant.now();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", code).param("domain", code.toLowerCase() + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                        """).param("companyId", companyId).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                        """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 21)).param("positionId", positionId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now) returning id
                        """).param("companyId", companyId).param("userId", userId)
                .param("email", suffix + "@example.com").param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:accountId, 'USER')")
                .param("accountId", accountId).update();
        UUID subject = UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:accountId, :subject, :now)")
                .param("accountId", accountId).param("subject", subject)
                .param("now", Timestamp.from(now)).update();
        String clientId = "public-" + suffix;
        long internalClientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'PKCE RP', 'ACTIVE', :trust,
                                :publicClient, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", clientId)
                .param("trust", consentRequired ? "CONSENT_REQUIRED" : "TRUSTED_FIRST_PARTY")
                .param("publicClient", rawSecret == null)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        if (rawSecret != null) {
            jdbcClient.sql("""
                            insert into oauth_client_secret(
                                client_id, secret_hash, secret_hint, created_at, version)
                            values (:clientId, :secretHash, :hint, :now, 0)
                            """).param("clientId", internalClientId)
                    .param("secretHash", new BCryptPasswordEncoder().encode(rawSecret))
                    .param("hint", rawSecret.substring(rawSecret.length() - 4))
                    .param("now", Timestamp.from(now)).update();
        }
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'AUTHORIZATION')
                        """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'openid')")
                .param("clientId", internalClientId).update();
        if (consentRequired) {
            jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'profile')")
                    .param("clientId", internalClientId).update();
        }
        return new Fixture(accountId, companyId, userId, subject, internalClientId, clientId, rawSecret);
    }

    private Instant jdbcInstant(String sql, String authorizationId) {
        return jdbcClient.sql(sql).param("id", authorizationId).query(Instant.class).single();
    }

    private Instant codeUsedAt(String authorizationId) {
        return jdbcClient.sql("select used_at from oauth_authorization_code where authorization_id = :id")
                .param("id", authorizationId).query(Instant.class).optional().orElse(null);
    }

    private long accessTokenCount(String authorizationId) {
        return jdbcClient.sql("select count(*) from oauth_access_token where authorization_id = :id")
                .param("id", authorizationId).query(Long.class).single();
    }

    private long refreshTokenCount(String authorizationId) {
        return jdbcClient.sql("select count(*) from oauth_refresh_token where authorization_id = :id")
                .param("id", authorizationId).query(Long.class).single();
    }

    private String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Endpoints(String authorizationPath, String tokenPath) { }
    private record Fixture(long accountId, long companyId, long userId, UUID subject,
            long internalClientId, String clientId, String rawSecret) { }
    private record IssuedCode(String code, String authorizationId) { }
    private record ExchangeResult(int status, String error) { }

}
