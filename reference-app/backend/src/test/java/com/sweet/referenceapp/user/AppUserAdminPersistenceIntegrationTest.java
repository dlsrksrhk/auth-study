package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppUserProvisioningService;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class AppUserAdminPersistenceIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppUserProvisioningService provisioning;
    @Autowired AppUserRepository users;

    @Test
    void roleChangeIsStoredAndIncrementsVersion() {
        var before = provisioning.provision(profile("admin-change", Set.of()));

        var after = tx.execute(status -> {
            var current = users.findByIdForUpdate(before.id()).orElseThrow();
            return users.updateAdministration(current.changeRoles(
                    Set.of(AppRole.APP_USER, AppRole.APP_ADMIN),
                    current.updatedAt().plusSeconds(1)));
        });

        assertThat(after.version()).isGreaterThan(before.version());
        var stored = tx.execute(status -> users.findById(before.id()).orElseThrow());
        assertThat(stored.roles()).contains(AppRole.APP_ADMIN);
        assertThat(stored.snapshot()).isEqualTo(before.snapshot());
        assertThat(stored.lastLoginAt()).isEqualTo(before.lastLoginAt());
        assertThat(stored.createdAt()).isEqualTo(before.createdAt());
    }

    @Test
    void statusChangeIsStoredAndChangesActiveAdministratorCount() {
        var provisioned = provisioning.provision(profile("status-change", Set.of()));
        var before = tx.execute(status -> {
            var current = users.findByIdForUpdate(provisioned.id()).orElseThrow();
            return users.updateAdministration(current.changeRoles(
                    Set.of(AppRole.APP_USER, AppRole.APP_ADMIN),
                    current.updatedAt().plusSeconds(1)));
        });
        long countBefore = tx.execute(status -> users.countActiveAdministrators());
        assertThat(countBefore).isEqualTo(1);

        var after = tx.execute(status -> {
            var current = users.findByIdForUpdate(before.id()).orElseThrow();
            return users.updateAdministration(current.changeStatus(
                    AppUserStatus.DISABLED, current.updatedAt().plusSeconds(1)));
        });

        assertThat(after.status()).isEqualTo(AppUserStatus.DISABLED);
        assertThat(after.roles()).contains(AppRole.APP_ADMIN);
        assertThat(after.version()).isGreaterThan(before.version());
        long countAfter = tx.execute(status -> users.countActiveAdministrators());
        assertThat(countAfter).isZero();
    }

    @Test
    void staleVersionIsRejectedWithoutChangingStoredAdministration() {
        var before = provisioning.provision(profile("stale", Set.of()));
        var stale = new AppUser(before.id(), before.issuer(), before.subject(), before.snapshot(),
                before.status(), Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), before.createdAt(),
                before.updatedAt().plusSeconds(1), before.lastLoginAt(), before.version() + 1);

        assertThatThrownBy(() -> tx.execute(status -> users.updateAdministration(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        var stored = tx.execute(status -> users.findById(before.id()).orElseThrow());
        assertThat(stored.roles()).containsExactly(AppRole.APP_USER);
        assertThat(stored.version()).isEqualTo(before.version());
    }

    @Test
    void noOpPreservesVersionAndUpdatedAt() {
        var before = provisioning.provision(profile("no-op", Set.of()));

        var after = tx.execute(status -> {
            var current = users.findByIdForUpdate(before.id()).orElseThrow();
            return users.updateAdministration(current.changeStatus(
                    current.status(), current.updatedAt().plusSeconds(1)));
        });

        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
    }

    @Test
    void exceptionRollsBackStatusRolesAndVersion() {
        var before = provisioning.provision(profile("rollback", Set.of()));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var current = users.findByIdForUpdate(before.id()).orElseThrow();
            users.updateAdministration(current
                    .changeStatus(AppUserStatus.DISABLED, current.updatedAt().plusSeconds(1))
                    .changeRoles(Set.of(AppRole.APP_USER, AppRole.APP_ADMIN),
                            current.updatedAt().plusSeconds(1)));
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);

        var stored = tx.execute(status -> users.findById(before.id()).orElseThrow());
        assertThat(stored.status()).isEqualTo(AppUserStatus.ACTIVE);
        assertThat(stored.roles()).containsExactly(AppRole.APP_USER);
        assertThat(stored.version()).isEqualTo(before.version());
    }
}
