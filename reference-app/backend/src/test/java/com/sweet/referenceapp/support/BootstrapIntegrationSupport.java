package com.sweet.referenceapp.support;

import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.net.URI;
import java.util.Set;
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
}
