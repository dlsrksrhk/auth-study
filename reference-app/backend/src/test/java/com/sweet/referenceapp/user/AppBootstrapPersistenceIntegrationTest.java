package com.sweet.referenceapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweet.referenceapp.support.BootstrapIntegrationSupport;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;

class AppBootstrapPersistenceIntegrationTest extends BootstrapIntegrationSupport {

    @Autowired AppBootstrapStateRepository bootstrapRepository;
    @Autowired AppUserRepository userRepository;

    @Test
    void administratorAndBootstrapCompletionPersistTogether() {
        var userId = tx.execute(status -> {
            var state = bootstrapRepository.findSingletonForUpdate();
            var user = AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", "candidate",
                    profile("candidate", Set.of()).snapshot(), Instant.EPOCH);
            userRepository.insertIfAbsent(user);
            var current = userRepository.findByIdentityForUpdate(user.issuer(), user.subject())
                    .orElseThrow();
            var promoted = userRepository.addAdministrator(current, Instant.EPOCH.plusSeconds(1));
            bootstrapRepository.complete(
                    state.complete(promoted.id(), Instant.EPOCH.plusSeconds(1)));
            assertThat(promoted.roles())
                    .containsExactlyInAnyOrder(AppRole.APP_USER, AppRole.APP_ADMIN);
            assertThat(promoted.version()).isGreaterThan(current.version());
            assertThat(promoted.lastLoginAt()).isEqualTo(current.lastLoginAt());
            return promoted.id();
        });

        assertThat(jdbc.queryForList(
                "select role from app_user_role where app_user_id=?", String.class, userId))
                .containsExactlyInAnyOrder("APP_USER", "APP_ADMIN");
        assertThat(jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class)).isEqualTo(userId);
    }

    @Test
    void repositoriesRequireAnExistingTransaction() {
        var user = user("no-transaction");

        assertThatThrownBy(bootstrapRepository::findSingletonForUpdate)
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> bootstrapRepository.complete(
                new com.sweet.referenceapp.user.domain.AppBootstrapState((short) 1, user.id(),
                        Instant.EPOCH, 0)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> userRepository.addAdministrator(user, Instant.EPOCH))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void staleUserVersionCannotAddAdministrator() {
        var user = user("stale-user");
        tx.executeWithoutResult(status -> userRepository.insertIfAbsent(user));
        tx.executeWithoutResult(status -> userRepository.updateSnapshot(
                user.replaceSnapshot(user.snapshot(), Instant.EPOCH.plusSeconds(1))));

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                userRepository.addAdministrator(user, Instant.EPOCH.plusSeconds(2))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(roleCount(user.id(), "APP_ADMIN")).isZero();
    }

    @Test
    void staleBootstrapVersionCannotOverwriteCompletion() {
        var first = user("first-bootstrap");
        var second = user("second-bootstrap");
        tx.executeWithoutResult(status -> {
            userRepository.insertIfAbsent(first);
            userRepository.insertIfAbsent(second);
        });
        var stale = tx.execute(status -> bootstrapRepository.findSingletonForUpdate());
        tx.executeWithoutResult(status -> bootstrapRepository.complete(
                stale.complete(first.id(), Instant.EPOCH)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> bootstrapRepository.complete(
                stale.complete(second.id(), Instant.EPOCH.plusSeconds(1)))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(bootstrappedUserId()).isEqualTo(first.id());
    }

    @Test
    void completedBootstrapCannotBeSavedAgain() {
        var user = user("completed-bootstrap");
        tx.executeWithoutResult(status -> userRepository.insertIfAbsent(user));
        tx.executeWithoutResult(status -> {
            var state = bootstrapRepository.findSingletonForUpdate();
            bootstrapRepository.complete(state.complete(user.id(), Instant.EPOCH));
        });
        var completed = tx.execute(status -> bootstrapRepository.findSingletonForUpdate());

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                bootstrapRepository.complete(completed)))
                .rootCause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap already completed");
        assertThat(bootstrappedUserId()).isEqualTo(user.id());
    }

    @Test
    void missingBootstrapStateIsRejected() {
        tx.executeWithoutResult(status -> jdbc.update("delete from app_bootstrap_state"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                bootstrapRepository.findSingletonForUpdate()))
                .rootCause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap state missing");
    }

    @Test
    void incompleteBootstrapStateCannotBeSavedAsCompleted() {
        var incomplete = tx.execute(status -> bootstrapRepository.findSingletonForUpdate());

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                bootstrapRepository.complete(incomplete)))
                .rootCause()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Completed bootstrap state required");
        assertThat(jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class)).isNull();
    }

    @Test
    void disabledUserCannotBePromotedByRepository() {
        var user = user("disabled-user");
        tx.executeWithoutResult(status -> {
            userRepository.insertIfAbsent(user);
            jdbc.update("update app_user set status='DISABLED' where id=?", user.id());
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var current = userRepository.findByIdentityForUpdate(user.issuer(), user.subject())
                    .orElseThrow();
            userRepository.addAdministrator(current, Instant.EPOCH.plusSeconds(1));
        })).rootCause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Active user required");
        assertThat(roleCount(user.id(), "APP_ADMIN")).isZero();
        assertThat(jdbc.queryForObject("select status from app_user where id=?", String.class,
                user.id())).isEqualTo("DISABLED");
    }

    @Test
    void addingExistingAdministratorDoesNotIncrementVersion() {
        var user = user("existing-admin");
        var promoted = tx.execute(status -> {
            userRepository.insertIfAbsent(user);
            var current = userRepository.findByIdentityForUpdate(user.issuer(), user.subject())
                    .orElseThrow();
            return userRepository.addAdministrator(current, Instant.EPOCH.plusSeconds(1));
        });

        var unchanged = tx.execute(status -> userRepository.addAdministrator(
                promoted, Instant.EPOCH.plusSeconds(2)));

        assertThat(unchanged.version()).isEqualTo(promoted.version());
        assertThat(unchanged.updatedAt()).isEqualTo(promoted.updatedAt());
        assertThat(roleCount(user.id(), "APP_ADMIN")).isEqualTo(1L);
    }

    @Test
    void mismatchedUserIdCannotAddAdministrator() {
        var user = user("mismatched-id");
        tx.executeWithoutResult(status -> userRepository.insertIfAbsent(user));
        var mismatched = new AppUser(UUID.randomUUID(), user.issuer(), user.subject(),
                user.snapshot(), user.status(), user.roles(), user.createdAt(), user.updatedAt(),
                user.lastLoginAt(), user.version());

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                userRepository.addAdministrator(mismatched, Instant.EPOCH.plusSeconds(1))))
                .rootCause()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("App user id does not match identity");
        assertThat(roleCount(user.id(), "APP_ADMIN")).isZero();
    }

    private AppUser user(String subject) {
        return AppUser.create(UUID.randomUUID(), "http://idp.localhost:8080", subject,
                profile(subject, Set.of()).snapshot(), Instant.EPOCH);
    }

    private long roleCount(UUID userId, String role) {
        return jdbc.queryForObject(
                "select count(*) from app_user_role where app_user_id=? and role=?",
                Long.class, userId, role);
    }

    private UUID bootstrappedUserId() {
        return jdbc.queryForObject(
                "select bootstrapped_user_id from app_bootstrap_state where singleton_key=1",
                UUID.class);
    }
}
