package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLoginProvisioningService;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppRole;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppAdminBootstrapConcurrencyIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLoginProvisioningService service;
    @Autowired AppBootstrapStateRepository states;

    @Test
    void simultaneousDifferentCandidatesProduceExactlyOneAdministrator() throws Exception {
        var results = raceCandidates("left", "right");

        assertThat(results).filteredOn(result -> result.roles().contains(AppRole.APP_ADMIN))
                .hasSize(1);
        assertSingleAdministrator(results.stream()
                .filter(result -> result.roles().contains(AppRole.APP_ADMIN)).findFirst().orElseThrow());
    }

    @Test
    void simultaneousSameCandidateProducesOneUserAndOneAdministrator() throws Exception {
        var results = raceCandidates("same", "same");

        assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
        assertThat(results).allSatisfy(result -> assertThat(result.roles())
                .containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN));
        assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(1L);
        assertSingleAdministrator(results.get(0));
    }

    @Test
    void secondCandidateActuallyWaitsForCommittedWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var winnerReady = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        try {
            var winner = executor.submit(() -> tx.execute(status -> {
                states.findSingletonForUpdate();
                winnerReady.countDown();
                awaitLatch(releaseWinner);
                return service.provision(profile("first", Set.of("COMPANY_ADMIN")));
            }));
            assertThat(winnerReady.await(5, TimeUnit.SECONDS)).isTrue();
            var backendPid = new CompletableFuture<Integer>();
            var pending = executor.submit(() -> tx.execute(status -> {
                backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return service.provision(profile("second", Set.of("COMPANY_ADMIN")));
            }));

            awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
            releaseWinner.countDown();
            var first = winner.get(10, TimeUnit.SECONDS);
            var second = pending.get(10, TimeUnit.SECONDS);

            assertThat(first.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
            assertThat(second.roles()).containsExactly(AppRole.APP_USER);
            assertSingleAdministrator(first);
        } finally {
            releaseWinner.countDown();
            shutdown(executor);
        }
    }

    @Test
    void failedLockedCandidateAllowsWaitingCandidateToBecomeAdministrator() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var winnerProvisioned = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        try {
            var failed = executor.submit(() -> tx.execute(status -> {
                var result = service.provision(profile("failed", Set.of("COMPANY_ADMIN")));
                winnerProvisioned.countDown();
                awaitLatch(releaseWinner);
                throw new IllegalStateException("forced winner rollback");
            }));
            assertThat(winnerProvisioned.await(5, TimeUnit.SECONDS)).isTrue();
            var backendPid = new CompletableFuture<Integer>();
            var successor = executor.submit(() -> tx.execute(status -> {
                backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return service.provision(profile("successor", Set.of("COMPANY_ADMIN")));
            }));

            awaitDatabaseLock(backendPid.get(5, TimeUnit.SECONDS));
            releaseWinner.countDown();
            assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("forced winner rollback");
            var result = successor.get(10, TimeUnit.SECONDS);

            assertThat(result.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_user where subject='failed'", Long.class)).isZero();
            assertSingleAdministrator(result);
        } finally {
            releaseWinner.countDown();
            shutdown(executor);
        }
    }

    private java.util.List<AppUserView> raceCandidates(String leftSubject, String rightSubject)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Callable<AppUserView> left = () -> tx.execute(status -> {
                awaitBarrier(barrier);
                return service.provision(profile(leftSubject, Set.of("COMPANY_ADMIN")));
            });
            Callable<AppUserView> right = () -> tx.execute(status -> {
                awaitBarrier(barrier);
                return service.provision(profile(rightSubject, Set.of("COMPANY_ADMIN")));
            });
            var leftFuture = executor.submit(left);
            var rightFuture = executor.submit(right);
            return java.util.List.of(leftFuture.get(10, TimeUnit.SECONDS),
                    rightFuture.get(10, TimeUnit.SECONDS));
        } finally {
            shutdown(executor);
        }
    }

    private void assertSingleAdministrator(AppUserView winner) {
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user_role where role='APP_ADMIN'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class)).isEqualTo(winner.id());
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Timed out waiting for candidate race", exception);
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
