package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.PostgresContainerConfiguration;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AppUserPersistenceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AppUserRepository repository;
    TransactionTemplate tx;

    @BeforeEach
    void transactions() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    @Test
    void bootstrapStartsUnassigned() {
        var row = jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
        assertThat(row.get("bootstrapped_user_id")).isNull();
        assertThat(row.get("bootstrapped_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from app_bootstrap_state", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void duplicateIdentityDoesNotDuplicateUserOrRoles() {
        var sub = UUID.randomUUID().toString();
        var snapshot = new ExternalUserSnapshot("same@example.test", null, null, null, Set.of());
        var first = user(sub, snapshot);
        var second = AppUser.create(UUID.randomUUID(), first.issuer(), sub, snapshot, Instant.EPOCH);
        tx.executeWithoutResult(status -> {
            assertThat(repository.insertIfAbsent(first)).isTrue();
            assertThat(repository.insertIfAbsent(second)).isFalse();
            assertThat(repository.findByIdentityForUpdate(first.issuer(), sub).orElseThrow().id())
                    .isEqualTo(first.id());
        });
        assertThat(jdbc.queryForObject("select count(*) from app_user_role where app_user_id=?",
                Long.class, first.id())).isEqualTo(1L);
    }

    @Test
    void repositoryRequiresAnExistingTransaction() {
        var candidate = user(UUID.randomUUID().toString(),
                new ExternalUserSnapshot(null, null, null, null, Set.of()));
        assertThatThrownBy(() -> repository.insertIfAbsent(candidate))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void duplicateEmailIsAllowedForDifferentIdentities() {
        var snapshot = new ExternalUserSnapshot("shared@example.test", null, null, null, Set.of());
        var first = user(UUID.randomUUID().toString(), snapshot);
        var second = user(UUID.randomUUID().toString(), snapshot);
        tx.executeWithoutResult(status -> {
            assertThat(repository.insertIfAbsent(first)).isTrue();
            assertThat(repository.insertIfAbsent(second)).isTrue();
        });
        assertThat(jdbc.queryForObject("select count(*) from app_user where email=?", Long.class,
                "shared@example.test")).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void snapshotRoundTripsAndVersionIncrementsWithoutChangingLocalFields() {
        var initial = new ExternalUserSnapshot(null, null, null, null, Set.of());
        var first = user(UUID.randomUUID().toString(), initial);
        var firstUpdateAt = Instant.parse("2026-01-01T00:00:00Z");
        var secondUpdateAt = Instant.parse("2026-01-02T00:00:00Z");
        var next = new ExternalUserSnapshot("next@example.test", "Next",
                Map.of("code", "DEMO", "name", "Demo"),
                Map.of("secondary_departments", java.util.List.of()), Set.of("HR_MANAGER"));
        var updated = tx.execute(status -> {
            repository.insertIfAbsent(first);
            return repository.updateSnapshot(first.replaceSnapshot(next, firstUpdateAt));
        });
        var updatedAgain = tx.execute(status -> repository.updateSnapshot(
                updated.replaceSnapshot(initial, secondUpdateAt)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.snapshot()).isEqualTo(next);
        assertThat(updatedAgain.version()).isEqualTo(2);
        assertThat(updatedAgain.snapshot()).isEqualTo(initial);
        assertThat(updatedAgain.createdAt()).isEqualTo(first.createdAt());
        assertThat(updatedAgain.status()).isEqualTo(first.status());
        assertThat(updatedAgain.roles()).containsExactly(AppRole.APP_USER);
    }

    @Test
    void updateRejectsStaleVersion() {
        var first = user(UUID.randomUUID().toString(),
                new ExternalUserSnapshot(null, null, null, null, Set.of()));
        tx.executeWithoutResult(status -> repository.insertIfAbsent(first));
        tx.executeWithoutResult(status -> repository.updateSnapshot(
                first.replaceSnapshot(first.snapshot(), Instant.now())));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.updateSnapshot(first)))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void transactionRollbackRemovesUserAndRole() {
        var first = user(UUID.randomUUID().toString(),
                new ExternalUserSnapshot(null, null, null, null, Set.of()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            repository.insertIfAbsent(first);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from app_user where id=?", Long.class, first.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_user_role where app_user_id=?",
                Long.class, first.id())).isZero();
    }

    @Test
    void roleInsertFailureRollsBackUser() {
        var first = user(UUID.randomUUID().toString(),
                new ExternalUserSnapshot(null, null, null, null, Set.of()));
        tx.executeWithoutResult(status -> {
            jdbc.execute("CREATE FUNCTION pg_temp.fail_role_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced role failure'; END $$");
            jdbc.execute("CREATE TRIGGER fail_role_insert BEFORE INSERT ON app_user_role FOR EACH ROW EXECUTE FUNCTION pg_temp.fail_role_insert()");
            assertThatThrownBy(() -> repository.insertIfAbsent(first))
                    .rootCause()
                    .hasMessageContaining("forced role failure");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from app_user where id=?", Long.class, first.id())).isZero();
    }

    @Test
    void databaseConstraintsRejectInvalidRows() {
        var missing = UUID.randomUUID();
        assertConstraintViolation("insert into app_user_role(app_user_id,role) values (?, 'APP_USER')", missing);
        var first = user(UUID.randomUUID().toString(), new ExternalUserSnapshot(null, null, null, null, Set.of()));
        tx.executeWithoutResult(status -> repository.insertIfAbsent(first));
        assertConstraintViolation("insert into app_user_role(app_user_id,role) values (?, 'APP_USER')", first.id());
        assertConstraintViolation("insert into app_bootstrap_state(singleton_key) values (2)");
        assertConstraintViolation("update app_bootstrap_state set bootstrapped_user_id=? where singleton_key=1", first.id());
        assertConstraintViolation("update app_user set status='UNKNOWN' where id=?", first.id());
    }

    private void assertConstraintViolation(String sql, Object... args) {
        var failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> tx.executeWithoutResult(status -> jdbc.update(sql, args)));
        assertThat(failure).as("constraint statement must fail").isNotNull();
        assertThat(hasDataIntegrityViolation(failure) || hasConstraintSqlState(failure))
                .as("expected DataIntegrityViolationException or SQLSTATE class 23, but got %s", failure)
                .isTrue();
    }

    private boolean hasDataIntegrityViolation(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConstraintSqlState(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private AppUser user(String subject, ExternalUserSnapshot snapshot) {
        return AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", subject, snapshot, Instant.EPOCH);
    }
}
