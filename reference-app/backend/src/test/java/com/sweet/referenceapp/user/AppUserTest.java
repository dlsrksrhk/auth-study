package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void snapshotRefreshPreservesLoginTimeAndLocalRoles() {
        var before = Instant.parse("2026-09-10T00:00:00Z");
        var snapshot = new ExternalUserSnapshot("a@example.test", "A", null, null, Set.of());
        var original = AppUser.create(UUID.randomUUID(), "https://idp.test", "sub", snapshot,
                before);

        var next = original.refreshSnapshot(snapshot, before.plusSeconds(60));

        assertThat(next.lastLoginAt()).isEqualTo(before);
        assertThat(next.updatedAt()).isEqualTo(before.plusSeconds(60));
        assertThat(next.roles()).isEqualTo(original.roles());
    }

    @Test
    void replacementPreservesLocalPolicyAndIdentity() {
        var t = Instant.parse("2026-09-08T00:00:00Z");
        var old = new ExternalUserSnapshot("a@example.test", "A", null, null,
                Set.of("COMPANY_ADMIN"));
        var user = new AppUser(UUID.randomUUID(), "http://idp.localhost:8080", "opaque-1",
                old, AppUserStatus.DISABLED, Set.of(AppRole.APP_USER, AppRole.APP_ADMIN),
                t, t, t, 7);
        var cleared = new ExternalUserSnapshot(null, null, null, null, null);

        var next = user.replaceSnapshot(cleared, t.plusSeconds(1));

        assertThat(next.id()).isEqualTo(user.id());
        assertThat(next.issuer()).isEqualTo(user.issuer());
        assertThat(next.subject()).isEqualTo(user.subject());
        assertThat(next.status()).isEqualTo(AppUserStatus.DISABLED);
        assertThat(next.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(next.snapshot()).isEqualTo(cleared);
        assertThat(next.createdAt()).isEqualTo(t);
        assertThat(next.updatedAt()).isEqualTo(t.plusSeconds(1));
        assertThat(next.lastLoginAt()).isEqualTo(t.plusSeconds(1));
        assertThat(next.version()).isEqualTo(7);
    }

    @Test
    void creationEstablishesActiveAppUserAndView() {
        var user = AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", "opaque-1",
                new ExternalUserSnapshot(null, null, null, null, Set.of()), Instant.EPOCH);

        assertThat(user.status()).isEqualTo(AppUserStatus.ACTIVE);
        assertThat(user.roles()).containsExactly(AppRole.APP_USER);
        assertThat(user.createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(user.updatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(user.lastLoginAt()).isEqualTo(Instant.EPOCH);
        assertThat(user.version()).isZero();
        assertThat(AppUserView.from(user).id()).isEqualTo(user.id());
    }

    @Test
    void constructorRejectsInvalidDomainStateAndFreezesRoles() {
        var roles = new java.util.HashSet<>(Set.of(AppRole.APP_USER));
        var user = new AppUser(UUID.randomUUID(), "issuer", "subject",
                new ExternalUserSnapshot(null, null, null, null, Set.of()),
                AppUserStatus.ACTIVE, roles, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 0);
        roles.add(AppRole.APP_ADMIN);

        assertThat(user.roles()).containsExactly(AppRole.APP_USER);
        assertThatThrownBy(() -> new AppUser(UUID.randomUUID(), "issuer", "subject",
                user.snapshot(), AppUserStatus.ACTIVE, Set.of(AppRole.APP_ADMIN),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppUser(UUID.randomUUID(), "issuer", "subject",
                user.snapshot(), AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewCanonicalConstructorFreezesRoles() {
        var roles = new HashSet<>(Set.of(AppRole.APP_USER));
        var view = new AppUserView(UUID.randomUUID(), "issuer", "subject",
                new ExternalUserSnapshot(null, null, null, null, Set.of()),
                AppUserStatus.ACTIVE, roles, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 0);

        roles.add(AppRole.APP_ADMIN);

        assertThat(view.roles()).containsExactly(AppRole.APP_USER);
        assertThatThrownBy(() -> view.roles().add(AppRole.APP_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
