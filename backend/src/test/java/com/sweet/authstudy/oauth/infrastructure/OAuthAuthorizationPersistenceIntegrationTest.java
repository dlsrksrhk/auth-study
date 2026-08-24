package com.sweet.authstudy.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sweet.authstudy.oauth.domain.OAuthAccessToken;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationCode;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthClientTrust;
import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import com.sweet.authstudy.oauth.domain.OAuthRefreshToken;
import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class OAuthAuthorizationPersistenceIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-21T00:00:05Z");
    private static final Instant CODE_EXPIRES_AT = Instant.parse("2026-08-21T00:01:05Z");
    private static final Instant TOKEN_EXPIRES_AT = Instant.parse("2026-08-21T00:05:05Z");
    private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-08-28T00:00:05Z");
    private static final UUID SUBJECT = UUID.fromString("db4a5a2f-493a-4fe0-b7b5-c6248f693bc5");
    private static final UUID FAMILY_ID = UUID.fromString("ca7fec29-933b-40f0-944d-60f66b1a42b6");

    @Autowired JdbcClient jdbcClient;
    @Autowired OAuthAuthorizationRepository authorizationRepository;
    @Autowired OAuthConsentRepository consentRepository;
    @Autowired PlatformTransactionManager transactionManager;
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void removeCommittedFixtures() {
        for (Fixture fixture : fixtures.reversed()) {
            jdbcClient.sql("""
                    delete from oauth_authorization where registered_client_id = :clientId
                    """).param("clientId", fixture.clientId()).update();
            jdbcClient.sql("delete from oauth_consent where registered_client_id = :clientId")
                    .param("clientId", fixture.clientId()).update();
            jdbcClient.sql("delete from oauth_client where id = :clientId")
                    .param("clientId", fixture.clientId()).update();
            jdbcClient.sql("delete from oauth_subject where account_id = :accountId")
                    .param("accountId", fixture.accountId()).update();
            jdbcClient.sql("delete from accounts where id = :accountId")
                    .param("accountId", fixture.accountId()).update();
            jdbcClient.sql("delete from users where id = :userId")
                    .param("userId", fixture.userId()).update();
            jdbcClient.sql("delete from positions where id = :positionId")
                    .param("positionId", fixture.positionId()).update();
            jdbcClient.sql("delete from companies where id = :companyId")
                    .param("companyId", fixture.companyId()).update();
        }
    }

    @Test
    void migration_uses_unique_sha256_hash_columns_and_has_no_raw_protocol_secret_columns() {
        List<String> columns = jdbcClient.sql("""
                        select table_name || '.' || column_name
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name in ('oauth_authorization_code', 'oauth_access_token', 'oauth_refresh_token')
                        order by table_name, column_name
                        """)
                .query(String.class).list();

        assertThat(columns)
                .contains("oauth_authorization_code.code_hash",
                        "oauth_access_token.access_token_hash",
                        "oauth_refresh_token.refresh_token_hash")
                .doesNotContain("oauth_authorization_code.code_value",
                        "oauth_access_token.access_token_value",
                        "oauth_refresh_token.refresh_token_value");

        List<String> uniqueColumns = jdbcClient.sql("""
                        select ccu.table_name || '.' || ccu.column_name
                        from information_schema.table_constraints tc
                        join information_schema.constraint_column_usage ccu
                          on ccu.constraint_catalog = tc.constraint_catalog
                         and ccu.constraint_schema = tc.constraint_schema
                         and ccu.constraint_name = tc.constraint_name
                        where tc.constraint_schema = 'public'
                          and tc.constraint_type = 'UNIQUE'
                          and ccu.table_name in ('oauth_authorization_code', 'oauth_access_token', 'oauth_refresh_token')
                        """)
                .query(String.class).list();

        assertThat(uniqueColumns).contains(
                "oauth_authorization_code.code_hash",
                "oauth_access_token.access_token_hash",
                "oauth_refresh_token.refresh_token_hash");
    }

    @Test
    void consent_is_unique_per_account_and_client_and_round_trips_normalized_scope_rows() {
        Fixture fixture = insertFixture("CONSENT");
        OAuthConsent saved = consentRepository.save(OAuthConsent.create(
                fixture.accountId(), fixture.clientId(), Set.of(" openid ", "profile"), CREATED_AT));

        OAuthConsent reloaded = consentRepository
                .findByAccountIdAndRegisteredClientId(fixture.accountId(), fixture.clientId())
                .orElseThrow();

        assertThat(saved.id()).isPositive();
        assertThat(reloaded.scopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(jdbcClient.sql("select scope from oauth_consent_scope where consent_id = :id")
                .param("id", saved.id()).query(String.class).list())
                .containsExactlyInAnyOrder("openid", "profile");
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into oauth_consent(principal_account_id, registered_client_id, created_at, updated_at)
                        values (:accountId, :clientId, :now, :now)
                        """)
                .param("accountId", fixture.accountId())
                .param("clientId", fixture.clientId())
                .param("now", Timestamp.from(CREATED_AT))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void saving_an_existing_consent_replaces_removed_scope_rows() {
        Fixture fixture = insertFixture("CONSENT_REPLACE");
        OAuthConsent saved = consentRepository.save(OAuthConsent.create(
                fixture.accountId(), fixture.clientId(), Set.of("openid", "profile"), CREATED_AT));
        OAuthConsent narrowed = OAuthConsent.restore(
                saved.id(), saved.principalAccountId(), saved.registeredClientId(), Set.of("openid"),
                saved.createdAt(), saved.updatedAt().plusSeconds(1));

        consentRepository.save(narrowed);

        assertThat(consentRepository.findByAccountIdAndRegisteredClientId(
                fixture.accountId(), fixture.clientId()).orElseThrow().scopes())
                .containsExactly("openid");
        assertThat(jdbcClient.sql("select scope from oauth_consent_scope where consent_id = :id")
                .param("id", saved.id()).query(String.class).list()).containsExactly("openid");
    }

    @Test
    void repository_round_trip_preserves_allowlisted_json_and_non_secret_protocol_metadata() {
        Fixture fixture = insertFixture("ROUND_TRIP");
        OAuthAuthorization authorization = authorization(fixture, "authorization-round-trip");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('a'), URI.create("https://rp.example/callback"),
                "A".repeat(43), "nonce-metadata", AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorization.attachAccessToken(OAuthAccessToken.issue(
                authorization.id(), hash('b'), "jti-1", "auth-study-userinfo",
                AUTHENTICATED_AT, TOKEN_EXPIRES_AT));
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('c'), FAMILY_ID, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));

        authorizationRepository.save(authorization);
        OAuthAuthorization reloaded = authorizationRepository.findById(authorization.id()).orElseThrow();

        assertThat(reloaded.registeredClientId()).isEqualTo(fixture.clientId());
        assertThat(reloaded.principalAccountId()).isEqualTo(fixture.accountId());
        assertThat(reloaded.subject()).isEqualTo(SUBJECT);
        assertThat(reloaded.companyId()).isEqualTo(fixture.companyId());
        assertThat(reloaded.authenticatedAt()).isEqualTo(AUTHENTICATED_AT);
        assertThat(reloaded.authorizedScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(reloaded.attributes()).isEqualTo(new OAuthAuthorization.Attributes(
                "principal@example.com", "https://idp.localhost:8080/oauth2/authorize"));
        assertThat(reloaded.authorizationCode()).get().satisfies(code -> {
            assertThat(code.redirectUri()).isEqualTo(URI.create("https://rp.example/callback"));
            assertThat(code.codeChallenge()).isEqualTo("A".repeat(43));
            assertThat(code.nonce()).isEqualTo("nonce-metadata");
        });
        assertThat(reloaded.accessToken()).get().satisfies(token -> {
            assertThat(token.jti()).isEqualTo("jti-1");
            assertThat(token.audience()).isEqualTo("auth-study-userinfo");
        });
        assertThat(reloaded.refreshToken()).get().satisfies(token ->
                assertThat(token.familyId()).isEqualTo(FAMILY_ID));

        String attributes = jdbcClient.sql("select attributes::text from oauth_authorization where id = :id")
                .param("id", authorization.id()).query(String.class).single();
        assertThat(attributes)
                .contains("principalName", "authorizationRequestUri")
                .doesNotContain("@class", "rO0AB");
    }

    @Test
    void code_lookup_locks_and_supports_exact_boundary_one_time_consumption() {
        Fixture fixture = insertFixture("CODE");
        OAuthAuthorization authorization = authorization(fixture, "authorization-code");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('d'), URI.create("https://rp.example/callback"),
                "B".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorizationRepository.save(authorization);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        OAuthAuthorizationCode.Consumption first = transaction.execute(status -> {
            OAuthAuthorizationCode code = authorizationRepository.findByCodeHashForUpdate(hash('d')).orElseThrow();
            OAuthAuthorizationCode.Consumption result = code.consume(CODE_EXPIRES_AT.minusNanos(1));
            authorizationRepository.saveAuthorizationCode(code);
            return result;
        });
        OAuthAuthorizationCode.Consumption replay = transaction.execute(status ->
                authorizationRepository.findByCodeHashForUpdate(hash('d')).orElseThrow()
                        .consume(CODE_EXPIRES_AT));

        assertThat(first).isEqualTo(OAuthAuthorizationCode.Consumption.CONSUMED);
        assertThat(replay).isEqualTo(OAuthAuthorizationCode.Consumption.ALREADY_USED);
    }

    @Test
    void authorization_code_is_expired_and_consumed_at_the_exact_expiry_boundary() {
        OAuthAuthorizationCode code = OAuthAuthorizationCode.issue(
                "authorization-boundary", hash('7'), URI.create("https://rp.example/callback"),
                "C".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT);

        assertThat(code.consume(CODE_EXPIRES_AT)).isEqualTo(OAuthAuthorizationCode.Consumption.EXPIRED);
        assertThat(code.usedAt()).isEqualTo(CODE_EXPIRES_AT);
        assertThat(code.consume(CODE_EXPIRES_AT.plusSeconds(1)))
                .isEqualTo(OAuthAuthorizationCode.Consumption.ALREADY_USED);
    }

    @Test
    void authorization_attributes_reject_non_allowlisted_polymorphic_type_metadata() {
        OAuthAuthorizationAttributesConverter converter = new OAuthAuthorizationAttributesConverter();

        assertThatThrownBy(() -> converter.convertToEntityAttribute("""
                {"principalName":"principal@example.com",
                 "authorizationRequestUri":"https://idp.localhost:8080/oauth2/authorize",
                 "@class":"java.lang.Runtime"}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void atomic_code_consumption_allows_only_one_of_two_real_postgresql_transactions_to_exchange() {
        Fixture fixture = insertFixture("CODE_RACE");
        OAuthAuthorization authorization = authorization(fixture, "authorization-code-race");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('5'), URI.create("https://rp.example/callback"),
                "D".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorizationRepository.save(authorization);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger exchanges = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var submissions = List.of(
                    executor.submit(() -> consumeCodeAfterBarrier(start, exchanges)),
                    executor.submit(() -> consumeCodeAfterBarrier(start, exchanges)));
            List<OAuthAuthorizationCode.Consumption> outcomes = submissions.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS).consumption();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(outcomes).containsExactlyInAnyOrder(
                    OAuthAuthorizationCode.Consumption.CONSUMED,
                    OAuthAuthorizationCode.Consumption.ALREADY_USED);
            assertThat(exchanges).hasValue(1);
        }
    }

    @Test
    void confirmed_invalid_code_exchange_is_committed_and_cannot_be_replayed() {
        Fixture fixture = insertFixture("CODE_INVALID");
        OAuthAuthorization authorization = authorization(fixture, "authorization-code-invalid");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('6'), URI.create("https://rp.example/callback"),
                "E".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorizationRepository.save(authorization);

        OAuthAuthorizationRepository.CodeConsumption<String> invalid = authorizationRepository
                .consumeCodeAtomically(hash('6'), AUTHENTICATED_AT.plusSeconds(1),
                        code -> "INVALID_REDIRECT_URI")
                .orElseThrow();
        OAuthAuthorizationRepository.CodeConsumption<String> replay = authorizationRepository
                .<String>consumeCodeAtomically(hash('6'), AUTHENTICATED_AT.plusSeconds(2),
                        code -> {
                            throw new AssertionError("Replay must not invoke exchange validation.");
                        })
                .orElseThrow();

        assertThat(invalid.consumption()).isEqualTo(OAuthAuthorizationCode.Consumption.CONSUMED);
        assertThat(invalid.exchangeResult()).contains("INVALID_REDIRECT_URI");
        assertThat(replay.consumption()).isEqualTo(OAuthAuthorizationCode.Consumption.ALREADY_USED);
        assertThat(replay.exchangeResult()).isEmpty();
    }

    @Test
    void atomic_code_callback_receives_fresh_locked_parent_authorization_and_client() {
        Fixture fixture = insertFixture("CODE_FRESH_CONTEXT");
        OAuthAuthorization authorization = authorization(fixture, "authorization-code-fresh-context");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('9'), URI.create("https://rp.example/callback"),
                "H".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorizationRepository.save(authorization);

        OAuthAuthorizationRepository.CodeConsumption<String> consumed = authorizationRepository
                .consumeCodeAtomically(hash('9'), AUTHENTICATED_AT.plusSeconds(1), exchange -> {
                    assertThat(exchange.code().authorizationId()).isEqualTo(authorization.id());
                    assertThat(exchange.authorization().id()).isEqualTo(authorization.id());
                    assertThat(exchange.authorization().status()).isEqualTo(OAuthAuthorization.Status.ACTIVE);
                    assertThat(exchange.client().id()).isEqualTo(fixture.clientId());
                    assertThat(exchange.client().status()).isEqualTo(OAuthClientStatus.ACTIVE);
                    assertThat(exchange.client().companyId()).isEqualTo(fixture.companyId());
                    return "VALID";
                }).orElseThrow();

        assertThat(consumed.exchangeResult()).contains("VALID");
    }

    @Test
    void caller_rollback_cannot_restore_a_code_after_confirmed_invalid_exchange() {
        Fixture fixture = insertFixture("CODE_OUTER_ROLLBACK");
        OAuthAuthorization authorization = authorization(fixture, "authorization-code-outer-rollback");
        authorization.attachAuthorizationCode(OAuthAuthorizationCode.issue(
                authorization.id(), hash('7'), URI.create("https://rp.example/callback"),
                "F".repeat(43), null, AUTHENTICATED_AT, CODE_EXPIRES_AT));
        authorizationRepository.save(authorization);
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            authorizationRepository.consumeCodeAtomically(
                    hash('7'), AUTHENTICATED_AT.plusSeconds(1), code -> "INVALID_PKCE")
                    .orElseThrow();
            status.setRollbackOnly();
        });
        OAuthAuthorizationRepository.CodeConsumption<String> replay = authorizationRepository
                .consumeCodeAtomically(hash('7'), AUTHENTICATED_AT.plusSeconds(2), code -> "MUST_NOT_RUN")
                .orElseThrow();

        assertThat(replay.consumption()).isEqualTo(OAuthAuthorizationCode.Consumption.ALREADY_USED);
        assertThat(replay.exchangeResult()).isEmpty();
    }

    @Test
    void two_real_postgresql_transactions_create_one_successor_then_revoke_the_whole_family_on_reuse()
            throws Exception {
        Fixture fixture = insertFixture("REFRESH_RACE");
        OAuthAuthorization authorization = authorization(fixture, "authorization-refresh-race");
        UUID raceFamilyId = UUID.randomUUID();
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('e'), raceFamilyId, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));
        authorizationRepository.save(authorization);

        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger successorSequence = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var submissions = List.of(
                    executor.submit(() -> rotateOrDetectReuse(start, successorSequence, raceFamilyId)),
                    executor.submit(() -> rotateOrDetectReuse(start, successorSequence, raceFamilyId)));
            List<RefreshOutcome> outcomes = submissions.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(outcomes).containsExactlyInAnyOrder(RefreshOutcome.ROTATED, RefreshOutcome.REUSED);
        }

        assertThat(jdbcClient.sql("select count(*) from oauth_refresh_token where family_id = :familyId")
                .param("familyId", raceFamilyId).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbcClient.sql("""
                        select count(*) from oauth_refresh_token
                        where family_id = :familyId and revoked_at is null
                """).param("familyId", raceFamilyId).query(Long.class).single()).isZero();
    }

    @Test
    void database_rejects_a_successor_from_another_refresh_family() {
        Fixture fixture = insertFixture("REFRESH_FAMILY_CONSTRAINT");
        OAuthAuthorization authorization = authorization(fixture, "authorization-family-constraint");
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('1'), FAMILY_ID, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));
        authorizationRepository.save(authorization);
        OAuthRefreshToken anotherFamily = authorizationRepository.saveRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('2'), UUID.randomUUID(), AUTHENTICATED_AT.plusSeconds(1),
                REFRESH_EXPIRES_AT));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        update oauth_refresh_token set successor_id = :successorId, used_at = :usedAt
                        where refresh_token_hash = :currentHash
                        """)
                .param("successorId", anotherFamily.id())
                .param("usedAt", Timestamp.from(AUTHENTICATED_AT.plusSeconds(1)))
                .param("currentHash", hash('1'))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_a_successor_that_extends_the_family_absolute_expiry() {
        Fixture fixture = insertFixture("REFRESH_EXPIRY_CONSTRAINT");
        OAuthAuthorization authorization = authorization(fixture, "authorization-expiry-constraint");
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('3'), FAMILY_ID, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));
        authorizationRepository.save(authorization);
        OAuthRefreshToken extended = authorizationRepository.saveRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('4'), FAMILY_ID, AUTHENTICATED_AT.plusSeconds(1),
                REFRESH_EXPIRES_AT.plusSeconds(1)));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        update oauth_refresh_token set successor_id = :successorId, used_at = :usedAt
                        where refresh_token_hash = :currentHash
                        """)
                .param("successorId", extended.id())
                .param("usedAt", Timestamp.from(AUTHENTICATED_AT.plusSeconds(1)))
                .param("currentHash", hash('3'))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void account_and_client_revocation_are_idempotent_and_revoke_authorization_access_and_refresh_rows() {
        Fixture fixture = insertFixture("REVOKE");
        OAuthAuthorization authorization = authorization(fixture, "authorization-revoke");
        authorization.attachAccessToken(OAuthAccessToken.issue(
                authorization.id(), hash('8'), "jti-revoke", "auth-study-userinfo",
                AUTHENTICATED_AT, TOKEN_EXPIRES_AT));
        authorization.attachRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('9'), UUID.randomUUID(), AUTHENTICATED_AT, REFRESH_EXPIRES_AT));
        authorizationRepository.save(authorization);
        Instant first = Instant.parse("2026-08-21T00:02:00Z");
        Instant second = Instant.parse("2026-08-21T00:03:00Z");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> authorizationRepository.revokeByAccountId(fixture.accountId(), first));
        transaction.executeWithoutResult(status -> authorizationRepository.revokeByClientId(fixture.clientId(), second));

        assertThat(jdbcClient.sql("select revoked_at from oauth_authorization where id = :id")
                .param("id", authorization.id()).query(Instant.class).single()).isEqualTo(first);
        assertThat(jdbcClient.sql("select revoked_at from oauth_access_token where authorization_id = :id")
                .param("id", authorization.id()).query(Instant.class).single()).isEqualTo(first);
        assertThat(jdbcClient.sql("select revoked_at from oauth_refresh_token where authorization_id = :id")
                .param("id", authorization.id()).query(Instant.class).single()).isEqualTo(first);
    }

    @Test
    void latest_access_token_breaks_equal_issued_at_ties_by_descending_id() {
        Fixture fixture = insertFixture("ACCESS_ORDER");
        OAuthAuthorization authorization = authorization(fixture, "authorization-access-order");
        authorizationRepository.save(authorization);
        authorizationRepository.saveAccessToken(OAuthAccessToken.issue(
                authorization.id(), hash('a'), "jti-order-first", "auth-study-userinfo",
                AUTHENTICATED_AT, TOKEN_EXPIRES_AT));
        OAuthAccessToken second = authorizationRepository.saveAccessToken(OAuthAccessToken.issue(
                authorization.id(), hash('b'), "jti-order-second", "auth-study-userinfo",
                AUTHENTICATED_AT, TOKEN_EXPIRES_AT));

        OAuthAuthorization reloaded = authorizationRepository.findById(authorization.id()).orElseThrow();

        assertThat(reloaded.accessToken()).get().extracting(OAuthAccessToken::id).isEqualTo(second.id());
    }

    @Test
    void latest_refresh_token_breaks_equal_issued_at_ties_by_descending_id() {
        Fixture fixture = insertFixture("REFRESH_ORDER");
        OAuthAuthorization authorization = authorization(fixture, "authorization-refresh-order");
        authorizationRepository.save(authorization);
        authorizationRepository.saveRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('c'), FAMILY_ID, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));
        OAuthRefreshToken second = authorizationRepository.saveRefreshToken(OAuthRefreshToken.issue(
                authorization.id(), hash('d'), FAMILY_ID, AUTHENTICATED_AT, REFRESH_EXPIRES_AT));

        OAuthAuthorization reloaded = authorizationRepository.findById(authorization.id()).orElseThrow();

        assertThat(reloaded.refreshToken()).get().extracting(OAuthRefreshToken::id).isEqualTo(second.id());
    }

    @Test
    void database_rejects_an_authorization_subject_owned_by_another_account() {
        Fixture subjectOwner = insertFixture("SUBJECT_OWNER");
        Fixture authorizationOwner = insertFixture("AUTHORIZATION_OWNER");

        assertThatThrownBy(() -> insertAuthorizationRow(
                "authorization-subject-mismatch", authorizationOwner.clientId(), subjectOwner.subject(),
                authorizationOwner.accountId(), authorizationOwner.companyId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_an_authorization_client_owned_by_another_company() {
        Fixture clientOwner = insertFixture("CLIENT_OWNER");
        Fixture authorizationOwner = insertFixture("CLIENT_AUTHORIZATION_OWNER");

        assertThatThrownBy(() -> insertAuthorizationRow(
                "authorization-client-mismatch", clientOwner.clientId(), authorizationOwner.subject(),
                authorizationOwner.accountId(), authorizationOwner.companyId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_rejects_an_authorization_account_owned_by_another_company() {
        Fixture accountOwner = insertFixture("ACCOUNT_OWNER");
        Fixture authorizationCompany = insertFixture("ACCOUNT_AUTHORIZATION_COMPANY");

        assertThatThrownBy(() -> insertAuthorizationRow(
                "authorization-account-mismatch", authorizationCompany.clientId(), accountOwner.subject(),
                accountOwner.accountId(), authorizationCompany.companyId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RefreshOutcome rotateOrDetectReuse(CyclicBarrier start, AtomicInteger successorSequence,
            UUID expectedFamilyId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            OAuthRefreshToken current = authorizationRepository.findRefreshByHashForUpdate(hash('e')).orElseThrow();
            assertThat(current.familyId()).isEqualTo(expectedFamilyId);
            Instant now = Instant.parse("2026-08-21T00:02:00Z");
            if (current.usedAt() != null) {
                authorizationRepository.revokeFamily(current.familyId(), now);
                return RefreshOutcome.REUSED;
            }
            char hashCharacter = (char) ('f' + successorSequence.getAndIncrement());
            OAuthRefreshToken successor = authorizationRepository.saveRefreshToken(OAuthRefreshToken.issue(
                    current.authorizationId(), hash(hashCharacter), current.familyId(), now, current.expiresAt()));
            current.markUsed(now, successor);
            authorizationRepository.saveRefreshToken(current);
            return RefreshOutcome.ROTATED;
        });
    }

    private OAuthAuthorizationRepository.CodeConsumption<String> consumeCodeAfterBarrier(
            CyclicBarrier start, AtomicInteger exchanges) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return authorizationRepository.consumeCodeAtomically(
                hash('5'), AUTHENTICATED_AT.plusSeconds(1), code -> {
                    exchanges.incrementAndGet();
                    return "EXCHANGED";
                }).orElseThrow();
    }

    private OAuthAuthorization authorization(Fixture fixture, String id) {
        OAuthClient client = OAuthClient.restore(
                fixture.clientId(), fixture.companyId(), "client-" + fixture.clientId(), "Client",
                OAuthClientStatus.ACTIVE, OAuthClientTrust.CONSENT_REQUIRED, true, 0,
                Set.of(URI.create("https://rp.example/callback")), Set.of(), Set.of("openid"),
                Set.of(), CREATED_AT, CREATED_AT);
        OAuthSubject subject = OAuthSubject.restore(
                fixture.accountId(), fixture.accountId(), fixture.subject(), CREATED_AT);
        return OAuthAuthorization.create(
                id, OAuthAuthorization.Ownership.verified(
                        client, subject, fixture.accountId(), fixture.companyId()),
                "authorization_code", Set.of("openid", "profile"),
                new OAuthAuthorization.Attributes(
                        "principal@example.com", "https://idp.localhost:8080/oauth2/authorize"),
                null, AUTHENTICATED_AT, CREATED_AT, REFRESH_EXPIRES_AT);
    }

    private int insertAuthorizationRow(String id, long clientId, UUID subject, long accountId, long companyId) {
        return jdbcClient.sql("""
                        insert into oauth_authorization(
                            id, registered_client_id, subject, principal_account_id, company_id,
                            authorization_grant_type, authorized_scopes, attributes, server_state_hash,
                            authenticated_at, status, created_at, expires_at)
                        values (:id, :clientId, :subject, :accountId, :companyId,
                                'authorization_code', 'openid', cast(:attributes as jsonb), null,
                                :authenticatedAt, 'ACTIVE', :createdAt, :expiresAt)
                        """)
                .param("id", id)
                .param("clientId", clientId)
                .param("subject", subject)
                .param("accountId", accountId)
                .param("companyId", companyId)
                .param("attributes", """
                        {"principalName":"principal@example.com",
                         "authorizationRequestUri":"https://idp.localhost:8080/oauth2/authorize"}
                        """)
                .param("authenticatedAt", Timestamp.from(AUTHENTICATED_AT))
                .param("createdAt", Timestamp.from(CREATED_AT))
                .param("expiresAt", Timestamp.from(REFRESH_EXPIRES_AT))
                .update();
    }

    private Fixture insertFixture(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = (prefix + "_" + suffix).toUpperCase();
        long companyId = jdbcClient.sql("""
                        insert into companies(code, name, email_domain, status, created_at, updated_at)
                        values (:code, :code, :domain, 'ACTIVE', :now, :now)
                        returning id
                        """)
                .param("code", code).param("domain", code.toLowerCase() + ".example")
                .param("now", Timestamp.from(CREATED_AT)).query(Long.class).single();
        long positionId = jdbcClient.sql("""
                        insert into positions(company_id, code, name, level, display_order, active, created_at, updated_at)
                        values (:companyId, 'EMPLOYEE', 'Employee', 1, 1, true, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId).param("now", Timestamp.from(CREATED_AT))
                .query(Long.class).single();
        long userId = jdbcClient.sql("""
                        insert into users(company_id, code, employee_number, name, phone, hired_at, workplace,
                                          position_id, status, created_at, updated_at)
                        values (:companyId, 'USER', :employeeNumber, 'User', '010-0000-0000', :hiredAt,
                                'Seoul', :positionId, 'ACTIVE', :now, :now)
                        returning id
                        """)
                .param("companyId", companyId).param("employeeNumber", "E-" + suffix)
                .param("hiredAt", LocalDate.of(2026, 8, 21)).param("positionId", positionId)
                .param("now", Timestamp.from(CREATED_AT)).query(Long.class).single();
        long accountId = jdbcClient.sql("""
                        insert into accounts(company_id, user_id, login_email, password_hash, status,
                                             must_change_password, created_at, updated_at)
                        values (:companyId, :userId, :email, 'hash', 'ACTIVE', false, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId).param("userId", userId)
                .param("email", suffix + "@example.com").param("now", Timestamp.from(CREATED_AT))
                .query(Long.class).single();
        UUID subject = prefix.equals("ROUND_TRIP") ? SUBJECT : UUID.randomUUID();
        jdbcClient.sql("insert into oauth_subject(account_id, subject, created_at) values (:accountId, :subject, :now)")
                .param("accountId", accountId).param("subject", subject).param("now", Timestamp.from(CREATED_AT))
                .update();
        long clientId = jdbcClient.sql("""
                        insert into oauth_client(company_id, client_id, display_name, status, trust,
                                                 public_client, created_at, updated_at)
                        values (:companyId, :externalId, :code, 'ACTIVE', 'CONSENT_REQUIRED', true, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId).param("externalId", "client-" + suffix)
                .param("code", code).param("now", Timestamp.from(CREATED_AT))
                .query(Long.class).single();
        jdbcClient.sql("insert into oauth_client_scope(client_id, scope) values (:clientId, 'openid')")
                .param("clientId", clientId).update();
        Fixture fixture = new Fixture(companyId, positionId, userId, accountId, clientId, subject);
        fixtures.add(fixture);
        return fixture;
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private enum RefreshOutcome { ROTATED, REUSED }

    private record Fixture(long companyId, long positionId, long userId, long accountId,
                           long clientId, UUID subject) { }
}
