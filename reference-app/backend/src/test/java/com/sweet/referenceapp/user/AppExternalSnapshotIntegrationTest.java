package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppExternalSnapshotService;
import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.ExternalIdentityProfile;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppExternalSnapshotIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLocalLoginService localLogin;
    @Autowired AppExternalSnapshotService snapshots;

    @Test
    void refreshUpdatesOnlyExternalSnapshotAndUpdatedTime() {
        var user = localLogin.login(profile("refresh-user", Set.of("COMPANY_ADMIN")));
        var before = userRow(user.id());
        var rolesBefore = roles(user.id());
        var bootstrapBefore = bootstrap();
        var changed = changedProfile("refresh-user");

        var refreshed = snapshots.refresh(user.id(), changed);

        var after = userRow(user.id());
        assertThat(refreshed.snapshot()).isEqualTo(changed.snapshot());
        assertThat(after).containsEntry("email", "changed@example.test")
                .containsEntry("display_name", "Changed User")
                .containsEntry("last_login_at", before.get("last_login_at"))
                .containsEntry("created_at", before.get("created_at"))
                .containsEntry("status", before.get("status"));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertThat(roles(user.id())).isEqualTo(rolesBefore);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void missingIdentityIsRejectedWithoutCreatingAUser() {
        var existing = localLogin.login(profile("existing", Set.of("EMPLOYEE")));
        var before = userRow(existing.id());
        var bootstrapBefore = bootstrap();

        assertUnavailable(() -> snapshots.refresh(UUID.randomUUID(), changedProfile("missing")));

        assertThat(userRow(existing.id())).isEqualTo(before);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void mismatchedUserIdIsRejectedWithoutChangingSnapshot() {
        var user = localLogin.login(profile("mismatch", Set.of("EMPLOYEE")));
        var before = userRow(user.id());
        var bootstrapBefore = bootstrap();

        assertUnavailable(() -> snapshots.refresh(UUID.randomUUID(), changedProfile("mismatch")));

        assertThat(userRow(user.id())).isEqualTo(before);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void disabledUserIsRejectedWithoutChangingSnapshot() {
        var user = localLogin.login(profile("disabled-refresh", Set.of("EMPLOYEE")));
        tx.executeWithoutResult(status -> jdbc.update(
                "update app_user set status='DISABLED', version=version+1 where id=?", user.id()));
        var before = userRow(user.id());
        var rolesBefore = roles(user.id());
        var bootstrapBefore = bootstrap();

        assertUnavailable(() -> snapshots.refresh(user.id(), changedProfile("disabled-refresh")));

        assertThat(userRow(user.id())).isEqualTo(before);
        assertThat(roles(user.id())).isEqualTo(rolesBefore);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
        assertThat(userCount()).isEqualTo(1);
    }

    private void assertUnavailable(Runnable refresh) {
        assertThatThrownBy(refresh::run)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Local user unavailable");
    }

    private ExternalIdentityProfile changedProfile(String subject) {
        return new ExternalIdentityProfile(URI.create("http://idp.localhost:8080"), subject,
                "changed@example.test", "Changed User",
                Map.of("code", "company-2", "name", "Company Two"),
                Map.of("position", Map.of("code", "lead", "name", "Lead")),
                Set.of("COMPANY_ADMIN", "AUDITOR"));
    }

    private Map<String, Object> userRow(UUID id) {
        return jdbc.queryForMap("select * from app_user where id=?", id);
    }

    private List<Map<String, Object>> roles(UUID id) {
        return jdbc.queryForList(
                "select * from app_user_role where app_user_id=? order by role", id);
    }

    private Map<String, Object> bootstrap() {
        return jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
    }

    private int userCount() {
        return jdbc.queryForObject("select count(*) from app_user", Integer.class);
    }
}
