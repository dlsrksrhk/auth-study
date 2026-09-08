package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.PostgresContainerConfiguration;
import com.sweet.referenceapp.user.application.AppUserProvisioningService;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.domain.AppRole;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
class AppUserProvisioningIntegrationTest {
    private static final String ISSUER = "http://idp.localhost:8080";

    @Autowired AppUserProvisioningService service;
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
    void repeatLoginKeepsIdentityWithoutBootstrapping() {
        var sub = UUID.randomUUID().toString();
        var a = service.provision(profile(ISSUER, sub, "a@example.test"));
        var b = service.provision(profile(ISSUER, sub, "b@example.test"));
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.snapshot().email()).isEqualTo("b@example.test");
        assertThat(b.roles()).containsExactly(AppRole.APP_USER);
        assertThat(b.version()).isGreaterThan(a.version());
    }

    @Test
    void issuerSubjectAndNotEmailDefineIdentity() {
        var sharedSub = UUID.randomUUID().toString();
        var first = service.provision(profile(ISSUER, sharedSub, "shared@example.test"));
        var otherIssuer = service.provision(profile("https://other.example", sharedSub, "shared@example.test"));
        var otherSubject = service.provision(profile(ISSUER, UUID.randomUUID().toString(), "shared@example.test"));
        assertThat(Set.of(first.id(), otherIssuer.id(), otherSubject.id())).hasSize(3);
    }

    @Test
    void absentOptionalClaimsRemoveTheWholePreviousSnapshot() {
        var sub = UUID.randomUUID().toString();
        var rich = new ExternalIdentityProfile(URI.create(ISSUER), sub, "old@example.test", "Old",
                Map.of("code", "DEMO", "name", "Demo"),
                Map.of("position", Map.of("code", "DEV", "name", "Developer"),
                        "secondary_departments", java.util.List.of()), Set.of("COMPANY_ADMIN"));
        service.provision(rich);
        var updated = service.provision(new ExternalIdentityProfile(URI.create(ISSUER), sub,
                null, null, null, null, null));
        assertThat(updated.snapshot().email()).isNull();
        assertThat(updated.snapshot().displayName()).isNull();
        assertThat(updated.snapshot().company()).isNull();
        assertThat(updated.snapshot().organization()).isNull();
        assertThat(updated.snapshot().hrRoles()).isEmpty();
    }

    @Test
    void localDisabledStatusAndAdminRoleSurviveLogin() {
        var sub = UUID.randomUUID().toString();
        var first = service.provision(profile(ISSUER, sub, "old@example.test"));
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from app_user where id=? for update", UUID.class, first.id());
            jdbc.update("update app_user set status='DISABLED', version=version+1 where id=?", first.id());
            jdbc.update("insert into app_user_role(app_user_id,role) values (?,'APP_ADMIN')", first.id());
        });
        var updated = service.provision(profile(ISSUER, sub, "new@example.test"));
        assertThat(updated.status().name()).isEqualTo("DISABLED");
        assertThat(updated.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(updated.snapshot().email()).isEqualTo("new@example.test");
    }

    @Test
    void outerRollbackRemovesNewUserAndRole() {
        var sub = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.provision(profile(ISSUER, sub, "new@example.test"));
            throw new IllegalStateException("outer failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outer failure");
        assertThat(count("select count(*) from app_user where issuer=? and subject=?", ISSUER, sub)).isZero();
        assertThat(count("select count(*) from app_user_role r join app_user u on u.id=r.app_user_id where u.issuer=? and u.subject=?", ISSUER, sub)).isZero();
    }

    @Test
    void outerRollbackRestoresExistingSnapshotAuditAndVersion() {
        var sub = UUID.randomUUID().toString();
        var original = service.provision(profile(ISSUER, sub, "old@example.test"));
        var before = jdbc.queryForMap("select email,updated_at,last_login_at,version from app_user where id=?", original.id());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.provision(profile(ISSUER, sub, "new@example.test"));
            throw new IllegalStateException("outer failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outer failure");
        var after = jdbc.queryForMap("select email,updated_at,last_login_at,version from app_user where id=?", original.id());
        assertThat(after).containsEntry("email", before.get("email"))
                .containsEntry("updated_at", before.get("updated_at"))
                .containsEntry("last_login_at", before.get("last_login_at"))
                .containsEntry("version", before.get("version"));
    }

    @Test
    void bootstrapLockAndProvisionParticipateInSameOuterRollback() {
        var sub = UUID.randomUUID().toString();
        var before = jdbc.queryForMap("select bootstrapped_user_id,bootstrapped_at,version from app_bootstrap_state where singleton_key=1");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select singleton_key from app_bootstrap_state where singleton_key=1 for update", Short.class);
            service.provision(profile(ISSUER, sub, "new@example.test"));
            throw new IllegalStateException("bootstrap failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("bootstrap failure");
        assertThat(count("select count(*) from app_user where issuer=? and subject=?", ISSUER, sub)).isZero();
        assertThat(jdbc.queryForMap("select bootstrapped_user_id,bootstrapped_at,version from app_bootstrap_state where singleton_key=1"))
                .isEqualTo(before);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private ExternalIdentityProfile profile(String issuer, String sub, String email) {
        return new ExternalIdentityProfile(URI.create(issuer), sub, email, "Name", null, null,
                Set.of("COMPANY_ADMIN"));
    }
}
