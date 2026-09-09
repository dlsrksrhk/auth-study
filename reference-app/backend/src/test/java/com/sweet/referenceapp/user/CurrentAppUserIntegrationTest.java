package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLoginProvisioningService;
import com.sweet.referenceapp.user.application.CurrentAppUserService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CurrentAppUserIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLoginProvisioningService provisioning;
    @Autowired CurrentAppUserService currentUsers;

    @Test
    void findsStoredUserWithRolesWithoutChangingDatabaseState() {
        var stored = provisioning.provision(profile("current", Set.of("COMPANY_ADMIN")));
        var usersBefore = users();
        var rolesBefore = roles();
        var bootstrapBefore = bootstrap();

        var found = currentUsers.find(stored.id());

        assertThat(found).hasValueSatisfying(user -> {
            assertThat(user.id()).isEqualTo(stored.id());
            assertThat(user.issuer()).isEqualTo(stored.issuer());
            assertThat(user.subject()).isEqualTo(stored.subject());
            assertThat(user.snapshot()).isEqualTo(stored.snapshot());
            assertThat(user.status()).isEqualTo(stored.status());
            assertThat(user.roles()).isEqualTo(stored.roles());
            assertThat(user.version()).isEqualTo(stored.version());
        });
        assertThat(users()).isEqualTo(usersBefore);
        assertThat(roles()).isEqualTo(rolesBefore);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
    }

    @Test
    void returnsEmptyForUnknownIdWithoutChangingDatabaseState() {
        provisioning.provision(profile("existing", Set.of("EMPLOYEE")));
        var usersBefore = users();
        var rolesBefore = roles();
        var bootstrapBefore = bootstrap();

        var found = currentUsers.find(UUID.randomUUID());

        assertThat(found).isEmpty();
        assertThat(users()).isEqualTo(usersBefore);
        assertThat(roles()).isEqualTo(rolesBefore);
        assertThat(bootstrap()).isEqualTo(bootstrapBefore);
    }

    private List<Map<String, Object>> users() {
        return jdbc.queryForList("select * from app_user order by id");
    }

    private List<Map<String, Object>> roles() {
        return jdbc.queryForList("select * from app_user_role order by app_user_id, role");
    }

    private Map<String, Object> bootstrap() {
        return jdbc.queryForMap("select * from app_bootstrap_state where singleton_key=1");
    }
}
