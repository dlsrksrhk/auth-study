package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminException.Code;
import com.sweet.referenceapp.user.application.AppUserAdminService;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppUserAdminIntegrationTest extends BootstrapIntegrationSupport {
    @Autowired AppLocalLoginService login;
    @Autowired AppUserAdminService admin;
    @Autowired AppUserRepository users;

    @Test
    void lastActiveAdministratorCannotDisableOrDemoteSelf() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var before = database();
        rejects(Code.LAST_ACTIVE_ADMIN_REQUIRED,
                () -> admin.changeStatus(actor.id(), actor.id(), AppUserStatus.DISABLED, actor.version()));
        rejects(Code.LAST_ACTIVE_ADMIN_REQUIRED,
                () -> admin.changeRoles(actor.id(), actor.id(), Set.of(AppRole.APP_USER), actor.version()));
        assertThat(database()).isEqualTo(before);
    }

    @Test
    void secondActiveAdministratorAllowsSelfDemotion() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        promote(actor, "second");
        var changed = admin.changeRoles(actor.id(), actor.id(), Set.of(AppRole.APP_USER), actor.version());
        assertThat(changed.roles()).containsExactly(AppRole.APP_USER);
        assertThat(changed.version()).isGreaterThan(actor.version());
        assertThat(tx.<Long>execute(s -> users.countActiveAdministrators())).isEqualTo(1L);
    }

    @Test
    void disabledAdministratorDoesNotAllowLastActiveAdministratorRemoval() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var second = promote(actor, "second");
        var disabled = admin.changeStatus(second.id(), second.id(), AppUserStatus.DISABLED, second.version());
        assertThat(disabled.status()).isEqualTo(AppUserStatus.DISABLED);
        assertThat(disabled.roles()).contains(AppRole.APP_ADMIN);
        rejects(Code.LAST_ACTIVE_ADMIN_REQUIRED,
                () -> admin.changeRoles(actor.id(), actor.id(), Set.of(AppRole.APP_USER), actor.version()));
        rejects(Code.LAST_ACTIVE_ADMIN_REQUIRED,
                () -> admin.changeStatus(actor.id(), actor.id(), AppUserStatus.DISABLED, actor.version()));
    }

    @Test
    void actorMustCurrentlyExistAndBeAnActiveLocalAdministratorBeforeTargetChecks() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var member = login.login(profile("member", Set.of("COMPANY_ADMIN")));
        var disabled = promote(actor, "disabled");
        admin.changeStatus(actor.id(), disabled.id(), AppUserStatus.DISABLED, disabled.version());
        var before = database();
        for (var id : Set.of(UUID.randomUUID(), member.id(), disabled.id())) {
            rejects(Code.FORBIDDEN,
                    () -> admin.changeStatus(id, UUID.randomUUID(), AppUserStatus.DISABLED, 999));
            rejects(Code.FORBIDDEN,
                    () -> admin.changeRoles(id, actor.id(), Set.of(AppRole.APP_USER), 999));
        }
        assertThat(database()).isEqualTo(before);
    }

    @Test
    void missingTargetPrecedesVersionCheck() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        rejects(Code.APP_USER_NOT_FOUND,
                () -> admin.changeStatus(actor.id(), UUID.randomUUID(), AppUserStatus.ACTIVE, 999));
        rejects(Code.APP_USER_NOT_FOUND,
                () -> admin.changeRoles(actor.id(), UUID.randomUUID(), Set.of(AppRole.APP_USER), 999));
    }

    @Test
    void sameValuesDoNotWriteButStaleVersionsStillConflictBeforeLastAdminCheck() {
        var loggedIn = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var actor = tx.execute(s -> AppUserView.from(users.findById(loggedIn.id()).orElseThrow()));
        var before = database();
        assertThat(admin.changeStatus(actor.id(), actor.id(), actor.status(), actor.version())).isEqualTo(actor);
        assertThat(admin.changeRoles(actor.id(), actor.id(), actor.roles(), actor.version())).isEqualTo(actor);
        rejects(Code.OPTIMISTIC_LOCK_CONFLICT,
                () -> admin.changeStatus(actor.id(), actor.id(), actor.status(), actor.version() + 1));
        rejects(Code.OPTIMISTIC_LOCK_CONFLICT,
                () -> admin.changeRoles(actor.id(), actor.id(), actor.roles(), actor.version() + 1));
        rejects(Code.OPTIMISTIC_LOCK_CONFLICT,
                () -> admin.changeStatus(actor.id(), actor.id(), AppUserStatus.DISABLED, actor.version() + 1));
        assertThat(database()).isEqualTo(before);
    }

    @Test
    void administrationPreservesIdentitySnapshotLoginAndBootstrap() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var member = login.login(profile("member", Set.of("EMPLOYEE", "AUDITOR")));
        var bootstrap = jdbc.queryForList("select * from app_bootstrap_state");
        var promoted = admin.changeRoles(actor.id(), member.id(),
                Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), member.version());
        var disabled = admin.changeStatus(actor.id(), member.id(), AppUserStatus.DISABLED, promoted.version());
        var active = admin.changeStatus(actor.id(), member.id(), AppUserStatus.ACTIVE, disabled.version());
        assertThat(active).usingRecursiveComparison()
                .ignoringFields("roles", "version", "updatedAt").isEqualTo(member);
        assertThat(active.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(active.version()).isGreaterThan(disabled.version());
        assertThat(active.updatedAt()).isAfter(member.updatedAt());
        assertThat(jdbc.queryForList("select * from app_bootstrap_state")).isEqualTo(bootstrap);
    }

    @Test
    void invalidInputsAreRejectedWithoutWrites() {
        var actor = login.login(profile("actor", Set.of("COMPANY_ADMIN")));
        var before = database();
        rejects(Code.INVALID_REQUEST, () -> admin.changeStatus(null, actor.id(), AppUserStatus.ACTIVE, 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeStatus(actor.id(), null, AppUserStatus.ACTIVE, 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeStatus(actor.id(), actor.id(), null, 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeStatus(actor.id(), actor.id(), AppUserStatus.ACTIVE, -1));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(null, actor.id(), actor.roles(), 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), null, actor.roles(), 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), actor.id(), null, 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), actor.id(), actor.roles(), -1));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), actor.id(), Set.of(), 0));
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), actor.id(), Set.of(AppRole.APP_ADMIN), 0));
        var nullRole = new HashSet<>(Set.of(AppRole.APP_USER));
        nullRole.add(null);
        rejects(Code.INVALID_REQUEST, () -> admin.changeRoles(actor.id(), actor.id(), nullRole, 0));
        assertThat(database()).isEqualTo(before);
    }

    private AppUserView promote(AppUserView actor, String subject) {
        var member = login.login(profile(subject, Set.of("EMPLOYEE")));
        return admin.changeRoles(actor.id(), member.id(), Set.of(AppRole.APP_USER, AppRole.APP_ADMIN), member.version());
    }

    private Object database() {
        return java.util.List.of(jdbc.queryForList("select * from app_user order by id"),
                jdbc.queryForList("select * from app_user_role order by app_user_id,role"),
                jdbc.queryForList("select * from app_bootstrap_state"));
    }

    private void rejects(Code code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppUserAdminException.class,
                exception -> assertThat(exception.code()).isEqualTo(code)).hasMessage(code.name());
    }
}
