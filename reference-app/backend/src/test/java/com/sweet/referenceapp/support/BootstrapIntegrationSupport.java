package com.sweet.referenceapp.support;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.application.name=reference-bootstrap-test")
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
public abstract class BootstrapIntegrationSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    protected TransactionTemplate tx;

    @BeforeEach
    void resetBootstrapDatabase() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from app_bootstrap_state");
            jdbc.update("delete from app_user");
            jdbc.update("insert into app_bootstrap_state(singleton_key) values (1)");
        });
    }

    protected ExternalIdentityProfile profile(String sub, Set<String> roles) {
        return new ExternalIdentityProfile(URI.create("http://idp.localhost:8080"),
                sub, "user@example.test", "User", null, null, roles);
    }

    protected void awaitDatabaseLock(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity "
                            + "where pid=? and wait_event_type='Lock')",
                    Boolean.class, pid))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected database lock wait");
    }

    protected void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination", exception);
        }
    }
}
