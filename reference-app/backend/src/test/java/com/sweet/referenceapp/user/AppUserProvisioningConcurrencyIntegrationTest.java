package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweet.referenceapp.support.PostgresContainerConfiguration;
import com.sweet.referenceapp.user.application.AppUserProvisioningService;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class AppUserProvisioningConcurrencyIntegrationTest {
    private static final String ISSUER = "http://idp.localhost:8080";

    @Autowired AppUserProvisioningService service;
    @Autowired AppUserRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    TransactionTemplate tx;

    @BeforeEach
    void configureTransactions() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    @Test
    void simultaneousFirstLoginsCreateOneIdentityTwentyTimes() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                var sub = UUID.randomUUID().toString();
                var barrier = new CyclicBarrier(2);
                Callable<AppUserView> login = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.provision(profile(ISSUER, sub, "same@example.test"));
                };
                var left = executor.submit(login);
                var right = executor.submit(login);
                assertThat(left.get(10, TimeUnit.SECONDS).id())
                        .isEqualTo(right.get(10, TimeUnit.SECONDS).id());
                assertThat(jdbc.queryForObject(
                        "select count(*) from app_user where issuer=? and subject=?",
                        Long.class, ISSUER, sub)).isEqualTo(1L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void conflictingInsertActuallyWaitsForWinnerAndReturnsItsUser() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var releaseWinner = new CountDownLatch(1);
        var winnerInserted = new CountDownLatch(1);
        var sub = UUID.randomUUID().toString();
        var winner = AppUser.create(UUID.randomUUID(), ISSUER, sub,
                profile(ISSUER, sub, "same@example.test").snapshot(), Instant.now());
        try {
            var committedWinner = executor.submit(() -> tx.execute(status -> {
                assertThat(repository.insertIfAbsent(winner)).isTrue();
                winnerInserted.countDown();
                awaitLatch(releaseWinner);
                return winner.id();
            }));
            assertThat(winnerInserted.await(5, TimeUnit.SECONDS)).isTrue();
            var backendPid = new CompletableFuture<Integer>();
            var pending = executor.submit(() -> tx.execute(status -> {
                backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return service.provision(profile(ISSUER, sub, "same@example.test"));
            }));
            awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
            releaseWinner.countDown();
            assertThat(committedWinner.get(10, TimeUnit.SECONDS)).isEqualTo(winner.id());
            assertThat(pending.get(10, TimeUnit.SECONDS).id()).isEqualTo(winner.id());
            assertThat(jdbc.queryForObject("select count(*) from app_user_role where app_user_id=?",
                    Long.class, winner.id())).isEqualTo(1L);
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void provisioningWaitsForLocalPolicyCommitAndPreservesIt() throws Exception {
        var sub = UUID.randomUUID().toString();
        var user = service.provision(profile(ISSUER, sub, "old@example.test"));
        var executor = Executors.newFixedThreadPool(2);
        var releaseLocal = new CountDownLatch(1);
        var localLocked = new CountDownLatch(1);
        try {
            var local = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from app_user where id=? for update", UUID.class, user.id());
                jdbc.update("update app_user set status='DISABLED', version=version+1 where id=?", user.id());
                jdbc.update("insert into app_user_role(app_user_id,role) values (?,'APP_ADMIN')", user.id());
                localLocked.countDown();
                awaitLatch(releaseLocal);
            }));
            assertThat(localLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var backendPid = new CompletableFuture<Integer>();
            var pending = executor.submit(() -> tx.execute(status -> {
                backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return service.provision(profile(ISSUER, sub, "new@example.test"));
            }));
            awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
            releaseLocal.countDown();
            local.get(10, TimeUnit.SECONDS);
            var result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.status().name()).isEqualTo("DISABLED");
            assertThat(result.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
            assertFreshPolicyAndSnapshot(user.id(), "new@example.test");
        } finally {
            releaseLocal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void localPolicyChangeWaitsForProvisionCommitAndSurvives() throws Exception {
        var sub = UUID.randomUUID().toString();
        var user = service.provision(profile(ISSUER, sub, "old@example.test"));
        var executor = Executors.newFixedThreadPool(2);
        var releaseProvision = new CountDownLatch(1);
        var provisioned = new CountDownLatch(1);
        try {
            var provisioning = executor.submit(() -> tx.execute(status -> {
                var result = service.provision(profile(ISSUER, sub, "new@example.test"));
                provisioned.countDown();
                awaitLatch(releaseProvision);
                return result;
            }));
            assertThat(provisioned.await(5, TimeUnit.SECONDS)).isTrue();
            var backendPid = new CompletableFuture<Integer>();
            var local = executor.submit(() -> tx.executeWithoutResult(status -> {
                backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                jdbc.queryForObject("select id from app_user where id=? for update", UUID.class, user.id());
                jdbc.update("update app_user set status='DISABLED', version=version+1 where id=?", user.id());
                jdbc.update("insert into app_user_role(app_user_id,role) values (?,'APP_ADMIN')", user.id());
            }));
            awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
            releaseProvision.countDown();
            assertThat(provisioning.get(10, TimeUnit.SECONDS).snapshot().email())
                    .isEqualTo("new@example.test");
            local.get(10, TimeUnit.SECONDS);
            assertFreshPolicyAndSnapshot(user.id(), "new@example.test");
        } finally {
            releaseProvision.countDown();
            executor.shutdownNow();
        }
    }

    private void assertFreshPolicyAndSnapshot(UUID id, String email) {
        var row = jdbc.queryForMap("select status,email from app_user where id=?", id);
        assertThat(row).containsEntry("status", "DISABLED").containsEntry("email", email);
        assertThat(jdbc.queryForList("select role from app_user_role where app_user_id=? order by role",
                String.class, id)).containsExactly("APP_ADMIN", "APP_USER");
    }

    private void awaitDatabaseLock(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                    Boolean.class, pid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected database lock wait");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination", exception);
        }
    }

    private ExternalIdentityProfile profile(String issuer, String sub, String email) {
        return new ExternalIdentityProfile(URI.create(issuer), sub, email, "Name", null, null,
                Set.of("COMPANY_ADMIN"));
    }
}
