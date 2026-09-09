package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.AppUserProvisioningService;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import com.sweet.referenceapp.user.application.LocalUserDisabledException;
import com.sweet.referenceapp.user.domain.AppRole;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppLocalLoginIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLocalLoginService localLogin;
    @Autowired AppUserProvisioningService provisioning;

    @Test
    void disabledUserLoginRollsBackSnapshotRoleAndBootstrapChanges() {
        var user = provisioning.provision(profile("disabled", Set.of("EMPLOYEE")));
        tx.executeWithoutResult(status -> jdbc.update(
                "update app_user set status='DISABLED', version=version+1 where id=?", user.id()));
        var before = jdbc.queryForMap("select * from app_user where id=?", user.id());
        var rolesBefore = roles();
        var bootstrapBefore = bootstrap();
        var changedProfile = new ExternalIdentityProfile(
                URI.create("http://idp.localhost:8080"), "disabled",
                "changed@example.test", "Changed", null, null, Set.of("COMPANY_ADMIN"));

        assertThatThrownBy(() -> localLogin.login(changedProfile))
                .isExactlyInstanceOf(LocalUserDisabledException.class)
                .hasMessage("Local user is disabled");

        assertThat(jdbc.queryForMap("select * from app_user where id=?", user.id()))
                .isEqualTo(before);
        assertThat(roles()).isEqualTo(rolesBefore);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
    }

    @Test
    void activeOrdinaryUserCanLoginWithoutConsumingBootstrap() {
        var user = localLogin.login(profile("ordinary", Set.of("EMPLOYEE")));

        assertThat(user.roles()).containsExactly(AppRole.APP_USER);
        assertThat(bootstrap().get("bootstrapped_user_id")).isNull();
    }

    @Test
    void firstEligibleUserCanLoginAsCompanyAdministrator() {
        var user = localLogin.login(profile("admin", Set.of("COMPANY_ADMIN")));

        assertThat(user.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(bootstrap().get("bootstrapped_user_id")).isEqualTo(user.id());
    }

    @Test
    void completedBootstrapDoesNotPromoteAnotherLoginCandidate() {
        var first = localLogin.login(profile("first", Set.of("COMPANY_ADMIN")));
        var completedBefore = bootstrap();

        var next = localLogin.login(profile("next", Set.of("COMPANY_ADMIN")));

        assertThat(next.roles()).containsExactly(AppRole.APP_USER);
        assertThat(bootstrap()).isEqualTo(completedBefore);
        assertThat(bootstrap().get("bootstrapped_user_id")).isEqualTo(first.id());
    }

    private List<Map<String, Object>> roles() {
        return jdbc.queryForList("select * from app_user_role order by app_user_id, role");
    }

    private Map<String, Object> bootstrap() {
        return jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
    }
}
