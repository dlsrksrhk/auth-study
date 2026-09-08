package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLoginProvisioningService;
import com.sweet.referenceapp.user.application.AppUserProvisioningService;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

class AppAdminBootstrapRollbackIntegrationTest extends BootstrapIntegrationSupport {
    private static final URI ISSUER = URI.create("http://idp.localhost:8080");

    @Autowired AppLoginProvisioningService service;
    @Autowired AppUserProvisioningService jitService;

    @ParameterizedTest(name = "{0}, existing={1}")
    @MethodSource("storageFailures")
    void storageFailureRollsBackJitRoleAndBootstrap(StorageFailure failure, boolean existing) {
        var fixture = fixture(existing, failure.name().toLowerCase());

        tx.executeWithoutResult(status -> {
            installFailure(failure);
            assertThatThrownBy(() -> service.provision(fixture.changedProfile()))
                    .rootCause()
                    .hasMessageContaining(failure.databaseMessage);
            status.setRollbackOnly();
        });

        assertFixtureRestored(fixture);
    }

    @ParameterizedTest(name = "existing={0}")
    @MethodSource("existingVariants")
    void outerTransactionRollbackRestoresNewAndExistingUsers(boolean existing) {
        var fixture = fixture(existing, "outer-" + existing);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.provision(fixture.changedProfile());
            throw new IllegalStateException("forced outer rollback");
        })).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("forced outer rollback");

        assertFixtureRestored(fixture);
    }

    @ParameterizedTest(name = "existing={0}")
    @MethodSource("existingVariants")
    void missingSingletonRollsBackNewAndExistingUsers(boolean existing) {
        var fixture = fixture(existing, "missing-" + existing);
        tx.executeWithoutResult(status -> jdbc.update("delete from app_bootstrap_state"));

        assertThatThrownBy(() -> service.provision(fixture.changedProfile()))
                .rootCause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap state missing");

        if (existing) {
            assertThat(userRow(fixture.existing().id())).isEqualTo(fixture.beforeUser());
            assertThat(roles(fixture.existing().id())).isEqualTo(fixture.beforeRoles());
        } else {
            assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_user_role", Long.class)).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from app_bootstrap_state", Long.class))
                .isZero();
    }

    private Fixture fixture(boolean existing, String subject) {
        AppUserView user = null;
        Map<String, Object> beforeUser = null;
        java.util.List<String> beforeRoles = null;
        if (existing) {
            user = jitService.provision(new ExternalIdentityProfile(ISSUER, subject,
                    "before@example.test", "Before",
                    Map.of("code", "BEFORE", "name", "Before Co"),
                    Map.of("position", Map.of("code", "OLD", "name", "Before Position")),
                    Set.of("EMPLOYEE")));
            beforeUser = userRow(user.id());
            beforeRoles = roles(user.id());
        }
        var changed = new ExternalIdentityProfile(ISSUER, subject, "after@example.test", "After",
                Map.of("code", "AFTER", "name", "After Co"),
                Map.of("primary_department",
                        Map.of("code", "NEW", "name", "After Department")),
                Set.of("COMPANY_ADMIN"));
        return new Fixture(user, beforeUser, beforeRoles,
                jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1"), changed);
    }

    private void assertFixtureRestored(Fixture fixture) {
        if (fixture.existing() == null) {
            assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_user_role", Long.class)).isZero();
        } else {
            assertThat(userRow(fixture.existing().id())).isEqualTo(fixture.beforeUser());
            assertThat(roles(fixture.existing().id())).isEqualTo(fixture.beforeRoles());
        }
        assertThat(jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1"))
                .isEqualTo(fixture.beforeBootstrap());
    }

    private void installFailure(StorageFailure failure) {
        if (failure == StorageFailure.ROLE) {
            jdbc.execute("""
                    CREATE FUNCTION fail_bootstrap_role() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'forced bootstrap role failure'; END $$
                    """);
            jdbc.execute("""
                    CREATE TRIGGER fail_bootstrap_role BEFORE INSERT ON app_user_role
                    FOR EACH ROW WHEN (NEW.role = 'APP_ADMIN')
                    EXECUTE FUNCTION fail_bootstrap_role()
                    """);
        } else {
            jdbc.execute("""
                    CREATE FUNCTION fail_bootstrap_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'forced bootstrap completion failure'; END $$
                    """);
            jdbc.execute("""
                    CREATE TRIGGER fail_bootstrap_completion BEFORE UPDATE ON app_bootstrap_state
                    FOR EACH ROW WHEN (NEW.bootstrapped_user_id IS NOT NULL)
                    EXECUTE FUNCTION fail_bootstrap_completion()
                    """);
        }
    }

    private Map<String, Object> userRow(java.util.UUID id) {
        return jdbc.queryForMap("select * from app_user where id=?", id);
    }

    private java.util.List<String> roles(java.util.UUID id) {
        return jdbc.queryForList(
                "select role from app_user_role where app_user_id=? order by role", String.class, id);
    }

    private static Stream<Arguments> storageFailures() {
        return Stream.of(StorageFailure.values())
                .flatMap(failure -> Stream.of(false, true)
                        .map(existing -> Arguments.of(failure, existing)));
    }

    private static Stream<Boolean> existingVariants() {
        return Stream.of(false, true);
    }

    private enum StorageFailure {
        ROLE("forced bootstrap role failure"),
        COMPLETION("forced bootstrap completion failure");

        private final String databaseMessage;

        StorageFailure(String databaseMessage) {
            this.databaseMessage = databaseMessage;
        }
    }

    private record Fixture(AppUserView existing, Map<String, Object> beforeUser,
            java.util.List<String> beforeRoles, Map<String, Object> beforeBootstrap,
            ExternalIdentityProfile changedProfile) {
    }
}
