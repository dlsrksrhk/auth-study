package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.user.domain.AppBootstrapState;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AppBootstrapStateTest {

    @Test
    void bootstrapCompletionCannotBeReassigned() {
        var state = new AppBootstrapState((short) 1, null, null, 0);
        var id = UUID.randomUUID();

        var done = state.complete(id, Instant.EPOCH);

        assertThat(done.completed()).isTrue();
        assertThat(done.bootstrappedUserId()).isEqualTo(id);
        assertThatThrownBy(() -> done.complete(UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap already completed");
    }

    @ParameterizedTest
    @MethodSource("invalidStates")
    void bootstrapStateRejectsInvalidInvariants(short key, UUID userId, Instant at, long version) {
        assertThatThrownBy(() -> new AppBootstrapState(key, userId, at, version))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidStates() {
        return Stream.of(
                Arguments.of((short) 0, null, null, 0L),
                Arguments.of((short) 2, null, null, 0L),
                Arguments.of((short) 1, null, null, -1L),
                Arguments.of((short) 1, UUID.randomUUID(), null, 0L),
                Arguments.of((short) 1, null, Instant.EPOCH, 0L));
    }

    @Test
    void activeUserBecomesAdministratorWhilePreservingExistingFields() {
        var user = user(AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER));
        var now = Instant.EPOCH.plusSeconds(1);

        var promoted = user.withAdministrator(now);

        assertThat(promoted.roles()).containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
        assertThat(promoted.id()).isEqualTo(user.id());
        assertThat(promoted.issuer()).isEqualTo(user.issuer());
        assertThat(promoted.subject()).isEqualTo(user.subject());
        assertThat(promoted.snapshot()).isEqualTo(user.snapshot());
        assertThat(promoted.status()).isEqualTo(user.status());
        assertThat(promoted.createdAt()).isEqualTo(user.createdAt());
        assertThat(promoted.updatedAt()).isEqualTo(now);
        assertThat(promoted.lastLoginAt()).isEqualTo(user.lastLoginAt());
        assertThat(promoted.version()).isEqualTo(user.version());
    }

    @Test
    void disabledUserCannotBecomeAdministrator() {
        var user = user(AppUserStatus.DISABLED, Set.of(AppRole.APP_USER));

        assertThatThrownBy(() -> user.withAdministrator(Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Active user required");
    }

    @Test
    void existingAdministratorIsReturnedUnchanged() {
        var user = user(AppUserStatus.ACTIVE, Set.of(AppRole.APP_USER, AppRole.APP_ADMIN));

        assertThat(user.withAdministrator(Instant.EPOCH.plusSeconds(1))).isSameAs(user);
    }

    private AppUser user(AppUserStatus status, Set<AppRole> roles) {
        var createdAt = Instant.parse("2026-09-08T00:00:00Z");
        var updatedAt = createdAt.plusSeconds(1);
        var lastLoginAt = createdAt.plusSeconds(2);
        return new AppUser(UUID.randomUUID(), "http://idp.localhost:8080", "candidate",
                new ExternalUserSnapshot("user@example.test", "User", null, null,
                        Set.of("COMPANY_ADMIN")),
                status, roles, createdAt, updatedAt, lastLoginAt, 7);
    }
}
