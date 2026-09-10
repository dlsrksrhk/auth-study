package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppAdminTransactionSettings;
import com.sweet.referenceapp.user.application.AppExternalSnapshotService;
import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminService;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AppUserAdminConcurrencyIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLocalLoginService login;
    @Autowired AppUserAdminService admin;
    @Autowired AppExternalSnapshotService snapshots;
    @Autowired AppUserRepository users;
    @Autowired AppBootstrapStateRepository states;
    @Autowired AppAdminTransactionSettings settings;
    @Autowired DataSource dataSource;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simultaneousSelfRemovalLeavesExactlyOneActiveAdministrator(boolean disable) throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { awaitLatch(start); return removal(a, a, disable); });
            var second = pool.submit(() -> { awaitLatch(start); return removal(b, b, disable); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "LAST_ACTIVE_ADMIN_REQUIRED");
            assertThat(tx.<Long>execute(s -> users.countActiveAdministrators())).isEqualTo(1L);
        } finally {
            start.countDown();
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitingRequestReloadsCachedActorAfterPermissionWasRevoked(boolean disable) throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var target = login.login(profile("target", Set.of("EMPLOYEE")));
        var before = jdbc.queryForMap("select * from app_user where id=?", target.id());
        var result = serialize(() -> {
            assertThat(removal(a, b, disable)).isEqualTo("OK");
            return "committed";
        }, () -> {
            // Populate the waiting transaction's persistence context from the old committed row.
            var cached = users.findById(b.id()).orElseThrow();
            assertThat(cached.roles()).contains(AppRole.APP_ADMIN);
            assertThat(cached.status()).isEqualTo(AppUserStatus.ACTIVE);
            return outcome(() -> admin.changeStatus(b.id(), target.id(), AppUserStatus.DISABLED, target.version()));
        });
        assertThat(result).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForMap("select * from app_user where id=?", target.id())).isEqualTo(before);
        assertThat(read(target.id()).roles()).containsExactly(AppRole.APP_USER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitingLastAdminRemovalCountsCommittedGrantOrActivation(boolean activate) throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var member = login.login(profile("b", Set.of("EMPLOYEE")));
        var b = member;
        if (activate) {
            var promoted = admin.changeRoles(a.id(), member.id(),
                    Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), member.version());
            b = admin.changeStatus(a.id(), member.id(), AppUserStatus.DISABLED, promoted.version());
        }
        var candidate = b;
        var result = serialize(() -> activate
                        ? admin.changeStatus(a.id(), candidate.id(), AppUserStatus.ACTIVE, candidate.version())
                        : admin.changeRoles(a.id(), candidate.id(), Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), candidate.version()),
                () -> removal(a, a, activate));
        assertThat(result).isEqualTo("OK");
        assertThat(read(b.id()).status()).isEqualTo(AppUserStatus.ACTIVE);
        assertThat(read(b.id()).roles()).contains(AppRole.APP_ADMIN);
        assertThat(tx.<Long>execute(s -> users.countActiveAdministrators())).isEqualTo(1L);
    }

    @Test
    void administrationWaitsForBootstrapLoginCommit() throws Exception {
        var candidate = new AtomicReference<AppUserView>();
        var result = serialize(() -> {
            var a = login.login(profile("bootstrap", Set.of("COMPANY_ADMIN")));
            candidate.set(a);
            return a;
        }, () -> removal(candidate.get(), candidate.get(), true));
        assertThat(result).isEqualTo("LAST_ACTIVE_ADMIN_REQUIRED");
        assertThat(read(candidate.get().id()).status()).isEqualTo(AppUserStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select bootstrapped_user_id from app_bootstrap_state", UUID.class))
                .isEqualTo(candidate.get().id());
    }

    @Test
    void loginWaitingForDemotionPreservesLocalRolesAndBootstrapHistory() throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var bootstrap = jdbc.queryForList("select * from app_bootstrap_state");
        var loggedIn = serialize(() -> admin.changeRoles(a.id(), b.id(), Set.of(AppRole.APP_USER), b.version()),
                () -> login.login(profile("b", Set.of("COMPANY_ADMIN"))));
        assertThat(loggedIn.roles()).containsExactly(AppRole.APP_USER);
        assertThat(loggedIn.snapshot().hrRoles()).containsExactly("COMPANY_ADMIN");
        assertThat(jdbc.queryForList("select * from app_bootstrap_state")).isEqualTo(bootstrap);
    }

    @Test
    void snapshotWaitingForAdministrationPreservesDemotedRoles() throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var refreshed = serialize(() -> admin.changeRoles(a.id(), b.id(), Set.of(AppRole.APP_USER), b.version()),
                () -> snapshots.refresh(b.id(), changedProfile("b")));
        assertThat(refreshed.roles()).containsExactly(AppRole.APP_USER);
        assertThat(refreshed.snapshot()).isEqualTo(changedProfile("b").snapshot());
        assertThat(refreshed.lastLoginAt()).isEqualTo(b.lastLoginAt());
    }

    @Test
    void administrationWaitingForSnapshotDetectsVersionConflictAndCanRetryLatestVersion() throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var result = serialize(() -> snapshots.refresh(b.id(), changedProfile("b")),
                () -> outcome(() -> admin.changeRoles(a.id(), b.id(), Set.of(AppRole.APP_USER), b.version())));
        assertThat(result).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        var current = read(b.id());
        assertThat(current.roles()).contains(AppRole.APP_ADMIN);
        var changed = admin.changeRoles(a.id(), b.id(), Set.of(AppRole.APP_USER), current.version());
        assertThat(changed.snapshot()).isEqualTo(changedProfile("b").snapshot());
        assertThat(changed.roles()).containsExactly(AppRole.APP_USER);
    }

    @Test
    void singletonLockTimeoutRollsBackAllWritesAndRestoresSettingsOnSameConnection() throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var b = promote(a, "b");
        var before = database();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new CompletableFuture<Integer>();
        var restored = new CompletableFuture<List<String>>();
        var baseline = new AtomicReference<List<String>>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> tx.execute(s -> {
                states.findSingletonForUpdate();
                held.countDown();
                awaitLatch(release);
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(s -> {
                        observeConnectionRestoration(baseline, restored);
                        pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        // This real write must also roll back when the following mutation times out.
                        jdbc.update("update app_user set display_name='must roll back' where id=?", b.id());
                        admin.changeStatus(a.id(), b.id(), AppUserStatus.DISABLED, b.version());
                    });
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            awaitDatabaseLock(pid.get(5, TimeUnit.SECONDS));
            var failure = pending.get(5, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(sqlCause(failure).getSQLState()).isEqualTo("55P03");
            assertThat(sqlCause(failure).getMessage()).contains("lock timeout");
            assertThat(restored.get(5, TimeUnit.SECONDS)).isEqualTo(baseline.get());
            assertThat(database()).isEqualTo(before);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @Test
    void successfulMutationUsesLocalTimeoutsAndRestoresSettingsOnSameConnection() throws Exception {
        var a = login.login(profile("a", Set.of("COMPANY_ADMIN")));
        var restored = new CompletableFuture<List<String>>();
        var baseline = new AtomicReference<List<String>>();
        tx.executeWithoutResult(s -> {
            observeConnectionRestoration(baseline, restored);
            admin.changeStatus(a.id(), a.id(), AppUserStatus.ACTIVE, a.version());
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("3s");
            assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("5s");
        });
        assertThat(restored.get(5, TimeUnit.SECONDS)).isEqualTo(baseline.get());
    }

    @Test
    void timeoutSettingsRequireAnExistingTransaction() {
        assertThatThrownBy(settings::apply).isInstanceOf(IllegalTransactionStateException.class);
    }

    private <T> T serialize(Supplier<?> firstAction, Supplier<T> waitingAction) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new CompletableFuture<Integer>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(s -> {
                var result = firstAction.get();
                held.countDown();
                awaitLatch(release);
                return result;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = pool.submit(() -> tx.execute(s -> {
                pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                var result = waitingAction.get();
                // Expected service rejections mark the joined transaction rollback-only.
                if (s.isRollbackOnly()) s.setRollbackOnly();
                return result;
            }));
            awaitDatabaseLock(pid.get(5, TimeUnit.SECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    private void observeConnectionRestoration(AtomicReference<List<String>> baseline,
            CompletableFuture<List<String>> restored) {
        var connection = DataSourceUtils.getConnection(dataSource);
        baseline.set(connectionSettings(connection));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    // Reuse the exact physical connection after commit/rollback, before pool release.
                    restored.complete(connectionSettings(connection));
                    connection.rollback();
                } catch (Exception exception) {
                    restored.completeExceptionally(exception);
                }
            }
        });
    }

    private List<String> connectionSettings(Connection connection) {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("select pg_backend_pid()::text, "
                        + "current_setting('lock_timeout'), current_setting('statement_timeout')")) {
            result.next();
            return List.of(result.getString(1), result.getString(2), result.getString(3));
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SQLException sqlCause(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
        }
        throw new AssertionError("Expected PostgreSQL lock timeout", failure);
    }

    private String removal(AppUserView actor, AppUserView target, boolean disable) {
        return outcome(() -> disable
                ? admin.changeStatus(actor.id(), target.id(), AppUserStatus.DISABLED, target.version())
                : admin.changeRoles(actor.id(), target.id(), Set.of(AppRole.APP_USER), target.version()));
    }

    private String outcome(Supplier<AppUserView> action) {
        try {
            action.get();
            return "OK";
        } catch (AppUserAdminException exception) {
            return exception.code().name();
        }
    }

    private AppUserView promote(AppUserView actor, String subject) {
        var member = login.login(profile(subject, Set.of("EMPLOYEE")));
        return admin.changeRoles(actor.id(), member.id(), Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), member.version());
    }

    private AppUserView read(UUID id) {
        return tx.execute(s -> AppUserView.from(users.findById(id).orElseThrow()));
    }

    private ExternalIdentityProfile changedProfile(String subject) {
        return new ExternalIdentityProfile(profile(subject, Set.of()).issuer(), subject,
                "changed@example.test", "Changed", Map.of("code", "company-2", "name", "Company Two"),
                Map.of("primary_department", Map.of("code", "team-2", "name", "Team Two")),
                Set.of("COMPANY_ADMIN", "AUDITOR"));
    }

    private Object database() {
        return List.of(jdbc.queryForList("select * from app_user order by id"),
                jdbc.queryForList("select * from app_user_role order by app_user_id,role"),
                jdbc.queryForList("select * from app_bootstrap_state"));
    }

    private void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
