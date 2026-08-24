package com.sweet.authstudy.oauth.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.oauth.infrastructure.SpringOAuth2AuthorizationService;
import com.sweet.authstudy.identity.application.AccountService;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.application.CredentialAuthenticationService;
import com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.oauth.application.OAuthClientCommands.UpdateClient;
import com.sweet.authstudy.oauth.application.OAuthClientService;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class,
        OAuthRefreshAndRevocationIntegrationTest.MutableClockConfiguration.class})
@ActiveProfiles("test")
class OAuthRefreshAndRevocationIntegrationTest {

    private static final URI CALLBACK = URI.create("https://refresh-rp.example/callback");
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String PASSWORD = "RefreshPassword1234!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private MutableClock clock;
    @Autowired private AccountService accountService;
    @Autowired private AuthenticationService authenticationService;
    @Autowired private CredentialAuthenticationService credentialAuthenticationService;
    @Autowired private UserService userService;
    @Autowired private CompanyService companyService;
    @Autowired private OAuthClientService oauthClientService;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoSpyBean private SpringOAuth2AuthorizationService springAuthorizationService;

    @AfterEach
    void useSystemTime() {
        clock.useSystemTime();
    }

    @Test
    void sequential_refresh_reuse_revokes_the_successor_before_returning_invalid_grant() throws Exception {
        Fixture fixture = fixture();
        TokenPair initial = issueTokens(fixture);
        Instant familyIssuedAt = refreshInstant(initial.refreshToken(), "issued_at");
        Instant familyExpiresAt = refreshInstant(initial.refreshToken(), "expires_at");
        assertThat(Duration.between(familyIssuedAt, familyExpiresAt)).isEqualTo(Duration.ofDays(7));

        clearInvocations(springAuthorizationService);
        TokenPair rotated = refresh(fixture, initial.refreshToken(), 200);
        assertThat(refreshInstant(rotated.refreshToken(), "expires_at")).isEqualTo(familyExpiresAt);
        verify(springAuthorizationService, never()).save(any());

        refresh(fixture, initial.refreshToken(), 400);
        refresh(fixture, rotated.refreshToken(), 400);

        assertThat(jdbcClient.sql("""
                        select count(*) from oauth_refresh_token
                         where family_id = (
                               select family_id from oauth_refresh_token where refresh_token_hash = :hash)
                           and revoked_at is not null
                        """).param("hash", sha256(initial.refreshToken()))
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbcClient.sql("""
                        select successor_id is not null and used_at is not null
                          from oauth_refresh_token where refresh_token_hash = :hash
                        """).param("hash", sha256(initial.refreshToken()))
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("""
                        select refresh_token_hash from oauth_refresh_token
                         where family_id = (
                               select family_id from oauth_refresh_token where refresh_token_hash = :hash)
                         order by id
                        """).param("hash", sha256(initial.refreshToken())).query(String.class).list())
                .containsExactly(sha256(initial.refreshToken()), sha256(rotated.refreshToken()))
                .doesNotContain(initial.refreshToken(), rotated.refreshToken());
    }

    @Test
    void concurrent_refresh_reuse_has_one_winner_then_revokes_its_successor() throws Exception {
        Fixture fixture = fixture();
        TokenPair initial = issueTokens(fixture);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentRefresh(fixture, initial.refreshToken(), barrier));
            var second = executor.submit(() -> concurrentRefresh(fixture, initial.refreshToken(), barrier));
            List<RefreshResponse> responses = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(responses).extracting(RefreshResponse::status)
                    .containsExactlyInAnyOrder(200, 400);
            RefreshResponse winner = responses.stream()
                    .filter(response -> response.status() == 200).findFirst().orElseThrow();
            assertThat(responses.stream().filter(response -> response.status() == 400).findFirst().orElseThrow().error())
                    .isEqualTo("invalid_grant");
            refresh(fixture, winner.refreshToken(), 400);
            assertThat(jdbcClient.sql("""
                            select count(*) from oauth_refresh_token
                             where family_id = (
                                   select family_id from oauth_refresh_token where refresh_token_hash = :hash)
                               and revoked_at is not null
                            """).param("hash", sha256(initial.refreshToken()))
                    .query(Long.class).single()).isEqualTo(2L);
        }
    }

    @Test
    void refresh_rechecks_current_consent_policy_and_exact_family_expiry() throws Exception {
        Fixture consentChanged = fixture();
        TokenPair consentTokens = issueTokens(consentChanged);
        jdbcClient.sql("update oauth_client set trust = 'CONSENT_REQUIRED' where client_id = :clientId")
                .param("clientId", consentChanged.clientId()).update();

        refresh(consentChanged, consentTokens.refreshToken(), 400);

        Fixture expired = fixture();
        TokenPair expiredTokens = issueTokens(expired);
        Instant absoluteExpiry = refreshInstant(expiredTokens.refreshToken(), "expires_at");
        clock.set(absoluteExpiry);
        refresh(expired, expiredTokens.refreshToken(), 400);
        assertThat(jdbcClient.sql("""
                        select successor_id from oauth_refresh_token where refresh_token_hash = :hash
                        """).param("hash", sha256(expiredTokens.refreshToken()))
                .query(Long.class).optional()).isEmpty();
    }

    @Test
    void refresh_rechecks_current_account_user_company_authorization_and_refresh_status() throws Exception {
        Fixture disabledAccount = fixture();
        TokenPair accountTokens = issueTokens(disabledAccount);
        jdbcClient.sql("update accounts set status = 'DISABLED' where id = :id")
                .param("id", disabledAccount.accountId()).update();
        refresh(disabledAccount, accountTokens.refreshToken(), 400);

        Fixture lockedUser = fixture();
        TokenPair userTokens = issueTokens(lockedUser);
        jdbcClient.sql("update users set status = 'LOCKED' where id = :id")
                .param("id", lockedUser.userId()).update();
        refresh(lockedUser, userTokens.refreshToken(), 400);

        Fixture inactiveCompany = fixture();
        TokenPair companyTokens = issueTokens(inactiveCompany);
        jdbcClient.sql("update companies set status = 'INACTIVE' where id = :id")
                .param("id", inactiveCompany.companyId()).update();
        refresh(inactiveCompany, companyTokens.refreshToken(), 400);

        Fixture revokedAuthorization = fixture();
        TokenPair authorizationTokens = issueTokens(revokedAuthorization);
        jdbcClient.sql("""
                        update oauth_authorization
                           set status = 'REVOKED', revocation_reason = 'TEST', revoked_at = :now
                         where id = :id
                        """).param("now", Timestamp.from(clock.instant()))
                .param("id", authorizationId(authorizationTokens.refreshToken())).update();
        refresh(revokedAuthorization, authorizationTokens.refreshToken(), 400);

        Fixture revokedRefresh = fixture();
        TokenPair refreshTokens = issueTokens(revokedRefresh);
        jdbcClient.sql("update oauth_refresh_token set revoked_at = :now where refresh_token_hash = :hash")
                .param("now", Timestamp.from(clock.instant()))
                .param("hash", sha256(refreshTokens.refreshToken())).update();
        refresh(revokedRefresh, refreshTokens.refreshToken(), 400);
    }

    @Test
    void account_lock_expiry_is_allowed_at_the_exact_boundary_but_not_before_it() throws Exception {
        Fixture fixture = fixture();
        TokenPair tokens = issueTokens(fixture);
        Instant boundary = clock.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbcClient.sql("update accounts set locked_until = :boundary where id = :id")
                .param("boundary", Timestamp.from(boundary)).param("id", fixture.accountId()).update();
        assertThat(jdbcClient.sql("select locked_until from accounts where id = :id")
                .param("id", fixture.accountId()).query(Instant.class).single()).isEqualTo(boundary);
        clock.set(boundary);

        TokenPair atBoundary = refresh(fixture, tokens.refreshToken(), 200);

        jdbcClient.sql("update accounts set locked_until = :lockedUntil where id = :id")
                .param("lockedUntil", Timestamp.from(boundary.plusSeconds(1)))
                .param("id", fixture.accountId()).update();
        refresh(fixture, atBoundary.refreshToken(), 400);
    }

    @Test
    void temporary_password_reset_revokes_oauth_authorization_and_refresh_in_the_same_use_case() throws Exception {
        Fixture fixture = fixture();
        TokenPair tokens = issueTokens(fixture);
        String authorizationId = authorizationId(tokens.refreshToken());

        accountService.resetTemporaryPassword(fixture.accountId());

        assertThat(jdbcClient.sql("select status from oauth_authorization where id = :id")
                .param("id", authorizationId).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(jdbcClient.sql("select revoked_at from oauth_refresh_token where refresh_token_hash = :hash")
                .param("hash", sha256(tokens.refreshToken())).query(Instant.class).optional()).isPresent();
    }

    @Test
    void password_change_revokes_oauth_authorization_and_refresh_in_the_same_use_case() throws Exception {
        Fixture fixture = fixture();
        TokenPair tokens = issueTokens(fixture);

        authenticationService.changePassword(
                new AuthenticatedAccount(fixture.accountId(), fixture.companyId(), fixture.userId(),
                        Set.of(AccountRole.USER), false),
                new ChangePasswordCommand(PASSWORD, "ChangedRefreshPassword1234!"));

        assertGrantRevoked(tokens.refreshToken());
    }

    @Test
    void login_lock_revokes_hr_refresh_and_oauth_grants_without_an_application_event() throws Exception {
        Fixture fixture = fixture();
        TokenPair tokens = issueTokens(fixture);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> credentialAuthenticationService.authenticate(
                    new CredentialAuthenticationService.Command(fixture.email(), "WrongPassword1234!")))
                    .isInstanceOf(com.sweet.authstudy.shared.error.ApiException.class);
        }

        assertThat(jdbcClient.sql("select locked_until from accounts where id = :id")
                .param("id", fixture.accountId()).query(Instant.class).optional()).isPresent();
        assertGrantRevoked(tokens.refreshToken());
    }

    @Test
    void company_admin_assignment_and_revocation_each_revoke_current_oauth_grants() throws Exception {
        Fixture fixture = fixture();
        TokenPair beforeAssignment = issueTokens(fixture);

        accountService.assignCompanyAdmin(systemAdmin(), fixture.accountId());
        assertGrantRevoked(beforeAssignment.refreshToken());
        mockMvc.perform(get("/userinfo").header("Authorization", "Bearer " + beforeAssignment.accessToken()))
                .andExpect(status().isUnauthorized());

        TokenPair beforeRevocation = issueTokens(fixture);
        accountService.revokeCompanyAdmin(systemAdmin(), fixture.accountId());
        assertGrantRevoked(beforeRevocation.refreshToken());
    }

    @Test
    void account_disable_revokes_hr_refresh_and_oauth_grants() throws Exception {
        Fixture fixture = fixture();
        TokenPair tokens = issueTokens(fixture);

        accountService.disableAccount(fixture.accountId());

        assertThat(jdbcClient.sql("select status from accounts where id = :id")
                .param("id", fixture.accountId()).query(String.class).single()).isEqualTo("DISABLED");
        assertGrantRevoked(tokens.refreshToken());
    }

    @Test
    void user_disable_and_company_deactivation_revoke_current_oauth_grants() throws Exception {
        Fixture disabledUser = fixture();
        TokenPair userTokens = issueTokens(disabledUser);
        userService.changeStatus(systemAdmin(), disabledUser.companyCode(), "USER", UserStatus.LOCKED, 0);
        assertGrantRevoked(userTokens.refreshToken());

        Fixture inactiveCompany = fixture();
        TokenPair companyTokens = issueTokens(inactiveCompany);
        companyService.update(systemAdmin(), inactiveCompany.companyCode(),
                new UpdateCompanyCommand(inactiveCompany.companyCode(), CompanyStatus.INACTIVE, 0));
        assertGrantRevoked(companyTokens.refreshToken());
    }

    @Test
    void client_disable_and_secret_rotation_revoke_current_oauth_grants() throws Exception {
        Fixture disabled = fixture();
        TokenPair disabledTokens = issueTokens(disabled);
        var disabledView = oauthClientService.find(systemAdmin(), disabled.clientId());
        oauthClientService.update(systemAdmin(), disabled.clientId(), new UpdateClient(
                disabledView.displayName(), disabledView.redirectUris(), disabledView.postLogoutRedirectUris(),
                disabledView.scopes(), disabledView.trust(), OAuthClientStatus.DISABLED, disabledView.version()));
        assertGrantRevoked(disabledTokens.refreshToken());

        Fixture rotated = fixture();
        TokenPair rotatedTokens = issueTokens(rotated);
        oauthClientService.rotateSecret(systemAdmin(), rotated.clientId());
        assertGrantRevoked(rotatedTokens.refreshToken());

        Fixture revoked = fixture();
        TokenPair revokedTokens = issueTokens(revoked);
        oauthClientService.revokeSecret(systemAdmin(), revoked.clientId());
        assertGrantRevoked(revokedTokens.refreshToken());
    }

    private RefreshResponse concurrentRefresh(
            Fixture fixture, String refreshToken, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new RefreshResponse(result.getResponse().getStatus(),
                body.path("refresh_token").asText(null), body.path("error").asText(null));
    }

    private TokenPair issueTokens(Fixture fixture) throws Exception {
        MvcResult discoveryResult = mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk()).andReturn();
        JsonNode discovery = objectMapper.readTree(discoveryResult.getResponse().getContentAsByteArray());
        String authorizationPath = URI.create(discovery.path("authorization_endpoint").asText()).getPath();
        String tokenPath = URI.create(discovery.path("token_endpoint").asText()).getPath();
        String state = "state-" + UUID.randomUUID();
        MvcResult authorizationResult = mockMvc.perform(get(authorizationPath)
                        .with(user(Long.toString(fixture.accountId())))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", CALLBACK.toString())
                        .queryParam("scope", "openid")
                        .queryParam("state", state)
                        .queryParam("nonce", "nonce-" + UUID.randomUUID())
                        .queryParam("code_challenge", challenge(VERIFIER))
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String code = query(URI.create(authorizationResult.getResponse().getHeader("Location")), "code");
        MvcResult tokenResult = mockMvc.perform(post(tokenPath)
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", CALLBACK.toString())
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        return tokenPair(tokenResult);
    }

    private TokenPair refresh(Fixture fixture, String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.rawSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken))
                .andExpect(status().is(expectedStatus))
                .andExpect(expectedStatus == 200
                        ? jsonPath("$.refresh_token").isNotEmpty()
                        : jsonPath("$.error").value("invalid_grant"))
                .andReturn();
        return expectedStatus == 200 ? tokenPair(result) : new TokenPair(null, null);
    }

    private TokenPair tokenPair(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new TokenPair(json.path("access_token").asText(), json.path("refresh_token").asText());
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = clock.instant();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now) returning id
                        """).param("code", "REFRESH_" + suffix.toUpperCase())
                .param("domain", "refresh-" + suffix + ".example")
                .param("now", Timestamp.from(now)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now) returning id
                        """).param("companyId", companyId).param("now", Timestamp.from(now))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'Refresh User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, 'ACTIVE', :now, :now) returning id
                        """).param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 21)).param("positionId", positionId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        String email = suffix + "@refresh-" + suffix + ".example";
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, :passwordHash, 'ACTIVE', false, :now, :now) returning id
                        """).param("companyId", companyId).param("userId", userId)
                .param("email", email)
                .param("passwordHash", passwordEncoder.encode(PASSWORD)).param("now", Timestamp.from(now))
                .query(Long.class).single();
        jdbcClient.sql("insert into account_roles(account_id, role) values (:accountId, 'USER')")
                .param("accountId", accountId).update();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:accountId, :subject, :now)")
                .param("accountId", accountId).param("subject", UUID.randomUUID())
                .param("now", Timestamp.from(now)).update();
        String clientId = "refresh-client-" + suffix;
        String rawSecret = "refresh-secret-" + suffix;
        long internalClientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'Refresh RP', 'ACTIVE', 'TRUSTED_FIRST_PARTY',
                                false, :now, :now) returning id
                        """).param("companyId", companyId).param("clientId", clientId)
                .param("now", Timestamp.from(now)).query(Long.class).single();
        jdbcClient.sql("""
                        insert into oauth_client_secret(client_id, secret_hash, secret_hint, created_at, version)
                        values (:clientId, :secretHash, :hint, :now, 0)
                        """).param("clientId", internalClientId)
                .param("secretHash", new BCryptPasswordEncoder().encode(rawSecret))
                .param("hint", rawSecret.substring(rawSecret.length() - 4))
                .param("now", Timestamp.from(now)).update();
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, 'AUTHORIZATION')
                        """).param("clientId", internalClientId).param("redirectUri", CALLBACK.toString()).update();
        jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'openid')")
                .param("clientId", internalClientId).update();
        return new Fixture(accountId, companyId, userId, "REFRESH_" + suffix.toUpperCase(),
                clientId, rawSecret, email);
    }

    private String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Instant refreshInstant(String rawRefreshToken, String column) {
        return jdbcClient.sql("select " + column
                        + " from oauth_refresh_token where refresh_token_hash = :hash")
                .param("hash", sha256(rawRefreshToken)).query(Instant.class).single();
    }

    private String authorizationId(String rawRefreshToken) {
        return jdbcClient.sql("""
                        select authorization_id from oauth_refresh_token where refresh_token_hash = :hash
                        """).param("hash", sha256(rawRefreshToken)).query(String.class).single();
    }

    private void assertGrantRevoked(String rawRefreshToken) {
        String authorizationId = authorizationId(rawRefreshToken);
        assertThat(jdbcClient.sql("select status from oauth_authorization where id = :id")
                .param("id", authorizationId).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(jdbcClient.sql("select revoked_at from oauth_refresh_token where refresh_token_hash = :hash")
                .param("hash", sha256(rawRefreshToken)).query(Instant.class).optional()).isPresent();
    }

    private AuthenticatedAccount systemAdmin() {
        return new AuthenticatedAccount(999_999L, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false);
    }

    private record Fixture(long accountId, long companyId, long userId, String companyCode,
            String clientId, String rawSecret, String email) { }
    private record TokenPair(String accessToken, String refreshToken) { }
    private record RefreshResponse(int status, String refreshToken, String error) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(ZoneId.of("UTC"));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant fixed;
        private final ZoneId zone;

        MutableClock(ZoneId zone) {
            this.zone = zone;
        }

        void set(Instant fixed) {
            this.fixed = fixed;
        }

        void useSystemTime() {
            this.fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock copy = new MutableClock(zone);
            copy.fixed = fixed;
            return copy;
        }

        @Override
        public Instant instant() {
            Instant current = fixed;
            return current == null ? Instant.now() : current;
        }
    }
}
