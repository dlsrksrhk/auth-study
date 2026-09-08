package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLoginProvisioningService;
import com.sweet.referenceapp.user.domain.AppRole;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

class AppAdminBootstrapIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLoginProvisioningService service;

    @Test
    void firstEligibleUserGetsLocalAdminAndMatchingState() {
        var result = service.provision(profile("eligible", Set.of("COMPANY_ADMIN")));

        assertThat(result.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(result.version()).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class)).isEqualTo(result.id());
        assertThat(jdbc.queryForObject(
                "select bootstrapped_at from app_bootstrap_state where singleton_key=1",
                Timestamp.class)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("ordinaryHrRoleSets")
    void ordinaryUserDoesNotConsumeBootstrapBeforeEligibleCandidate(Set<String> hrRoles) {
        var ordinary = service.provision(profile("ordinary", hrRoles));

        assertThat(ordinary.roles()).containsExactly(AppRole.APP_USER);
        assertThat(bootstrappedUserId()).isNull();

        var eligible = service.provision(profile("eligible", Set.of("COMPANY_ADMIN")));

        assertThat(eligible.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(bootstrappedUserId()).isEqualTo(eligible.id());
    }

    private static Stream<Set<String>> ordinaryHrRoleSets() {
        return Stream.of(Set.of(), Set.of("EMPLOYEE"));
    }

    @Test
    void disabledCompanyAdminDoesNotConsumeBootstrapBeforeActiveCandidate() {
        var disabled = service.provision(profile("disabled", Set.of("EMPLOYEE")));
        disable(disabled.id());

        var disabledAgain = service.provision(profile("disabled", Set.of("COMPANY_ADMIN")));
        var active = service.provision(profile("active", Set.of("COMPANY_ADMIN")));

        assertThat(disabledAgain.status().name()).isEqualTo("DISABLED");
        assertThat(disabledAgain.roles()).containsExactly(AppRole.APP_USER);
        assertThat(active.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(bootstrappedUserId()).isEqualTo(active.id());
    }

    @Test
    void existingOrdinaryUserCanBecomeEligibleFromCurrentHrRoles() {
        var original = service.provision(profile("existing", Set.of("EMPLOYEE")));

        var promoted = service.provision(profile("existing", Set.of("COMPANY_ADMIN")));

        assertThat(promoted.id()).isEqualTo(original.id());
        assertThat(promoted.snapshot().hrRoles()).containsExactly("COMPANY_ADMIN");
        assertThat(promoted.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(bootstrappedUserId()).isEqualTo(promoted.id());
    }

    @Test
    void completedBootstrapDoesNotPromoteAnotherCandidate() {
        var first = service.provision(profile("first", Set.of("COMPANY_ADMIN")));
        var completedBefore = completedState();

        var next = service.provision(profile("next", Set.of("COMPANY_ADMIN")));

        assertThat(next.roles()).containsExactly(AppRole.APP_USER);
        assertThat(completedState()).isEqualTo(completedBefore);
        assertThat(bootstrappedUserId()).isEqualTo(first.id());
    }

    @Test
    void disablingFirstAdministratorDoesNotReopenBootstrap() {
        var first = service.provision(profile("first", Set.of("COMPANY_ADMIN")));
        var completedBefore = completedState();
        disable(first.id());

        var next = service.provision(profile("next", Set.of("COMPANY_ADMIN")));

        assertThat(next.roles()).containsExactly(AppRole.APP_USER);
        assertThat(completedState()).isEqualTo(completedBefore);
    }

    @Test
    void removingFirstAdministratorRoleDoesNotReopenBootstrap() {
        var first = service.provision(profile("first", Set.of("COMPANY_ADMIN")));
        var completedBefore = completedState();
        tx.executeWithoutResult(status -> jdbc.update(
                "delete from app_user_role where app_user_id=? and role='APP_ADMIN'", first.id()));

        var next = service.provision(profile("next", Set.of("COMPANY_ADMIN")));

        assertThat(next.roles()).containsExactly(AppRole.APP_USER);
        assertThat(completedState()).isEqualTo(completedBefore);
    }

    @Test
    void removingFirstAdministratorsHrRoleDoesNotReopenBootstrap() {
        var first = service.provision(profile("first", Set.of("COMPANY_ADMIN")));
        var completedBefore = completedState();

        var firstWithoutHrRole = service.provision(profile("first", Set.of("EMPLOYEE")));
        var next = service.provision(profile("next", Set.of("COMPANY_ADMIN")));

        assertThat(firstWithoutHrRole.roles())
                .containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(firstWithoutHrRole.snapshot().hrRoles()).containsExactly("EMPLOYEE");
        assertThat(next.roles()).containsExactly(AppRole.APP_USER);
        assertThat(completedState()).isEqualTo(completedBefore);
    }

    private UUID bootstrappedUserId() {
        return jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class);
    }

    private Map<String, Object> completedState() {
        return jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
    }

    private void disable(UUID id) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from app_user where id=? for update", UUID.class, id);
            jdbc.update("update app_user set status='DISABLED', version=version+1 where id=?", id);
        });
    }
}
