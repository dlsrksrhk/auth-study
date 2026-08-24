package com.sweet.authstudy.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import com.sweet.authstudy.oauth.infrastructure.OAuthSubjectRepositoryAdapter;
import com.sweet.authstudy.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500"
})
@Import({
        PostgresContainerConfiguration.class,
        OAuthSubjectPoolStarvationIntegrationTest.CoordinatedRepositoryConfiguration.class
})
@ActiveProfiles("test")
class OAuthSubjectPoolStarvationIntegrationTest {

    private static final int CALLERS = 4;

    @Autowired
    private OAuthSubjectService subjectService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void callers_equal_to_pool_size_converge_without_waiting_for_a_nested_connection() throws Exception {
        long accountId = insertAccount();
        CyclicBarrier start = new CyclicBarrier(CALLERS);

        List<OAuthSubject> subjects = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(CALLERS)) {
            List<Future<OAuthSubject>> futures = new ArrayList<>();
            for (int index = 0; index < CALLERS; index++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return subjectService.getOrCreate(accountId);
                }));
            }
            for (Future<OAuthSubject> future : futures) {
                subjects.add(future.get(5, TimeUnit.SECONDS));
            }
        }

        assertThat(subjects).extracting(OAuthSubject::subject)
                .containsOnly(subjects.getFirst().subject());
        assertThat(jdbc.queryForObject(
                "select count(*) from oauth_subject where account_id = ?",
                Long.class,
                accountId)).isEqualTo(1L);
    }

    @Test
    void ambient_transactions_equal_to_pool_size_use_one_connection_each() throws Exception {
        long accountId = insertAccount();
        CyclicBarrier start = new CyclicBarrier(CALLERS);
        CyclicBarrier ambientConnectionsAcquired = new CyclicBarrier(CALLERS);

        List<OAuthSubject> subjects = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(CALLERS)) {
            List<Future<OAuthSubject>> futures = new ArrayList<>();
            for (int index = 0; index < CALLERS; index++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    return Objects.requireNonNull(transaction.execute(status -> {
                        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
                        await(ambientConnectionsAcquired, "ambient transactions");
                        return subjectService.getOrCreate(accountId);
                    }));
                }));
            }
            for (Future<OAuthSubject> future : futures) {
                subjects.add(future.get(5, TimeUnit.SECONDS));
            }
        }

        assertThat(subjects).extracting(OAuthSubject::subject)
                .containsOnly(subjects.getFirst().subject());
        assertThat(jdbc.queryForObject(
                "select count(*) from oauth_subject where account_id = ?",
                Long.class,
                accountId)).isEqualTo(1L);
    }

    private long insertAccount() {
        String suffix = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-21T00:00:00Z"));
        return jdbc.queryForObject("""
                insert into accounts(login_email, password_hash, status, must_change_password, created_at, updated_at)
                values (?, 'hash', 'ACTIVE', false, ?, ?)
                returning id
                """, Long.class, "pool-" + suffix + "@example.com", now, now);
    }

    private static void await(CyclicBarrier barrier, String operation) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating " + operation + ".", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(operation + " did not reach the concurrency barrier.", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CoordinatedRepositoryConfiguration {

        @Bean
        @Primary
        OAuthSubjectRepository coordinatedOAuthSubjectRepository(
                OAuthSubjectRepositoryAdapter adapter) {
            return new CoordinatedFirstLookupRepository(adapter, CALLERS);
        }
    }

    private static final class CoordinatedFirstLookupRepository implements OAuthSubjectRepository {

        private final OAuthSubjectRepository delegate;
        private final CyclicBarrier firstLookupsCompleted;
        private final ThreadLocal<Boolean> firstLookup = ThreadLocal.withInitial(() -> true);

        private CoordinatedFirstLookupRepository(OAuthSubjectRepository delegate, int callers) {
            this.delegate = delegate;
            this.firstLookupsCompleted = new CyclicBarrier(callers);
        }

        @Override
        public Optional<OAuthSubject> findByAccountId(long accountId) {
            Optional<OAuthSubject> result = delegate.findByAccountId(accountId);
            if (firstLookup.get()) {
                firstLookup.set(false);
                awaitFirstLookups();
            }
            return result;
        }

        @Override
        public void insertIfAbsent(OAuthSubject subject) {
            delegate.insertIfAbsent(subject);
        }

        @Override
        public OAuthSubject save(OAuthSubject subject) {
            return delegate.save(subject);
        }

        private void awaitFirstLookups() {
            try {
                firstLookupsCompleted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while coordinating first subject lookups.", exception);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("Subject lookups did not reach the concurrency barrier.", exception);
            }
        }
    }
}
