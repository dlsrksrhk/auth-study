package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.oauth.application.OAuthClientCommands;
import com.sweet.authstudy.oauth.application.OAuthClientService;
import com.sweet.authstudy.oauth.application.OAuthSubjectService;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientSecret;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class OAuthClientPersistenceIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OAuthClientRepository clientRepository;

    @Autowired
    private OAuthSubjectRepository subjectRepository;

    @Autowired
    private OAuthSubjectService subjectService;

    @Autowired
    private OAuthClientService clientService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void migration_creates_oauth_client_table() {
        Integer tableCount = jdbcClient.sql("select count(*) from oauth_client")
                .query(Integer.class)
                .single();

        assertThat(tableCount).isZero();
    }

    @Test
    void database_rejects_duplicate_external_client_id() {
        long companyId = insertCompany("ACME");
        insertClient(companyId, "opaque-client");

        assertThatThrownBy(() -> insertClient(companyId, "opaque-client"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_keeps_one_opaque_subject_per_account() {
        long accountId = insertAccount("SUBJECT");
        UUID subject = UUID.fromString("0f3a99ed-681f-46ec-aabe-c4c9fa467762");
        insertSubject(accountId, subject);

        assertThatThrownBy(() -> insertSubject(accountId,
                UUID.fromString("df2b4e80-cf99-4c61-a755-423d56e88202")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_keeps_opaque_subject_values_unique() {
        UUID subject = UUID.fromString("0f3a99ed-681f-46ec-aabe-c4c9fa467762");
        insertSubject(insertAccount("SUBJECT_VALUE"), subject);

        long anotherAccountId = insertAccount("OTHER");
        assertThatThrownBy(() -> insertSubject(anotherAccountId, subject))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_duplicate_redirect_even_when_purpose_differs() {
        long clientPk = insertClient(insertCompany("REDIRECT"), "redirect-client");
        insertRedirect(clientPk, "https://rp.example/callback", "AUTHORIZATION");

        assertThatThrownBy(() ->
                insertRedirect(clientPk, "https://rp.example/callback", "POST_LOGOUT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_duplicate_client_scope() {
        long clientPk = insertClient(insertCompany("SCOPE"), "scope-client");
        insertScope(clientPk, "openid");

        assertThatThrownBy(() -> insertScope(clientPk, "openid"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void client_secret_table_stores_hash_and_has_no_plaintext_secret_column() {
        var columns = jdbcClient.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name = 'oauth_client_secret'
                        order by column_name
                        """)
                .query(String.class)
                .list();

        assertThat(columns)
                .contains("secret_hash")
                .doesNotContain("secret", "client_secret", "raw_secret", "plaintext_secret");
    }

    @Test
    void repository_round_trip_preserves_client_redirect_purposes_scopes_and_secret_hash() {
        long companyId = insertCompany("ROUNDTRIP");
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        OAuthClient original = OAuthClient.create(
                companyId,
                "round-trip-client",
                "Round trip client",
                false,
                Set.of(URI.create("https://rp.example/callback"),
                        URI.create("http://rp.localhost/callback")),
                Set.of(URI.create("https://rp.example/logout")),
                Set.of("openid", "profile", "hr.company"),
                OAuthClientTrust.CONSENT_REQUIRED,
                Set.of(OAuthClientSecret.create(
                        "$2a$10$D9nr1CSDZ2F0K1QeJCFsOO2VYk2MxSP8n2BIDaTz4lqf6WcB7upbC",
                        "upbC",
                        now,
                        null)),
                now);

        OAuthClient saved = clientRepository.save(original);
        OAuthClient reloaded = clientRepository.findByClientId("round-trip-client").orElseThrow();

        assertThat(saved.id()).isPositive();
        assertThat(reloaded.redirectUris()).containsExactlyInAnyOrder(
                URI.create("https://rp.example/callback"),
                URI.create("http://rp.localhost/callback"));
        assertThat(reloaded.postLogoutRedirectUris())
                .containsExactly(URI.create("https://rp.example/logout"));
        assertThat(reloaded.scopes()).containsExactlyInAnyOrder("openid", "profile", "hr.company");
        assertThat(reloaded.secrets()).singleElement().satisfies(secret -> {
            assertThat(secret.secretHash()).startsWith("$2a$10$");
            assertThat(secret.secretHint()).isEqualTo("upbC");
            assertThat(secret.revokedAt()).isNull();
        });
    }

    @Test
    void service_rotation_persists_one_new_active_secret_after_revoking_the_previous_one() {
        insertCompany("ROTATE_SERVICE");
        AuthenticatedAccount systemAdmin = new AuthenticatedAccount(
                1L, null, null, Set.of(AccountRole.SYSTEM_ADMIN), false);
        var command = new OAuthClientCommands.CreateClient(
                "ROTATE_SERVICE", "Rotation client", false,
                Set.of(URI.create("https://rp.example/callback")), Set.of(), Set.of("openid"),
                OAuthClientTrust.CONSENT_REQUIRED);
        var created = clientService.create(systemAdmin, command);

        var rotated = clientService.rotateSecret(systemAdmin, created.client().clientId());

        OAuthClient stored = clientRepository.findByClientId(created.client().clientId()).orElseThrow();
        assertThat(stored.secrets()).hasSize(2);
        assertThat(stored.secrets()).filteredOn(secret -> secret.revokedAt() == null)
                .singleElement().satisfies(secret ->
                        assertThat(passwordEncoder.matches(rotated.oneTimeSecret(), secret.secretHash())).isTrue());
        assertThat(stored.secrets()).filteredOn(secret -> secret.revokedAt() != null).hasSize(1);
    }

    @Test
    void subject_repository_round_trip_preserves_the_opaque_uuid() {
        long accountId = insertAccount("SUBJECT_ROUNDTRIP");
        UUID opaqueSubject = UUID.fromString("2c7ff812-1be9-4ed8-b55a-02842502696e");

        OAuthSubject saved = subjectRepository.save(OAuthSubject.create(
                accountId, opaqueSubject, Instant.parse("2026-08-21T00:00:00Z")));

        assertThat(saved.id()).isPositive();
        assertThat(subjectRepository.findByAccountId(accountId)).hasValueSatisfying(reloaded -> {
            assertThat(reloaded.accountId()).isEqualTo(accountId);
            assertThat(reloaded.subject()).isEqualTo(opaqueSubject);
            assertThat(reloaded.createdAt()).isEqualTo(Instant.parse("2026-08-21T00:00:00Z"));
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_get_or_create_for_the_same_account_returns_one_subject() throws Exception {
        long accountId = insertAccount("SUBJECT_RACE");
        int callers = 4;
        CyclicBarrier start = new CyclicBarrier(callers);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            var futures = IntStream.range(0, callers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return subjectService.getOrCreate(accountId);
                    }))
                    .toList();
            var subjects = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(20, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(subjects).extracting(OAuthSubject::subject).containsOnly(subjects.getFirst().subject());
            assertThat(subjects.getFirst().subject().version()).isEqualTo(4);
            Long storedRows = jdbcClient.sql("select count(*) from oauth_subject where account_id = :accountId")
                    .param("accountId", accountId)
                    .query(Long.class)
                    .single();
            assertThat(storedRows).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private long insertCompany(String code) {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :name, :domain, 'ACTIVE', :now, :now)
                        returning id
                        """)
                .param("code", code)
                .param("name", code)
                .param("domain", code.toLowerCase() + ".example")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    private long insertAccount(String prefix) {
        long companyId = insertCompany(prefix);
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', 'E-1', 'User', '010-0000-0000', :hiredAt, 'Seoul',
                                :positionId, 'ACTIVE', :now, :now)
                        returning id
                        """)
                .param("companyId", companyId)
                .param("hiredAt", LocalDate.parse("2026-08-21"))
                .param("positionId", positionId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        return jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .param("email", prefix.toLowerCase() + "@example.com")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    private long insertClient(long companyId, String clientId) {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :clientId, 'Test client', 'ACTIVE', 'CONSENT_REQUIRED',
                                false, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId)
                .param("clientId", clientId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    private void insertSubject(long accountId, UUID subject) {
        jdbcClient.sql("""
                        insert into oauth_subject(account_id, subject, created_at)
                        values (:accountId, :subject, :createdAt)
                        """)
                .param("accountId", accountId)
                .param("subject", subject)
                .param("createdAt", Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")))
                .update();
    }

    private void insertRedirect(long clientPk, String redirectUri, String purpose) {
        jdbcClient.sql("""
                        insert into oauth_client_redirect_uri(client_id, redirect_uri, purpose)
                        values (:clientId, :redirectUri, :purpose)
                        """)
                .param("clientId", clientPk)
                .param("redirectUri", redirectUri)
                .param("purpose", purpose)
                .update();
    }

    private void insertScope(long clientPk, String scope) {
        jdbcClient.sql("""
                        insert into oauth_client_scope(client_id, scope)
                        values (:clientId, :scope)
                        """)
                .param("clientId", clientPk)
                .param("scope", scope)
                .update();
    }
}
