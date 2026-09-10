package com.sweet.referenceapp.user.application;

import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.APP_USER_NOT_FOUND;
import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.FORBIDDEN;
import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.INVALID_REQUEST;
import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.LAST_ACTIVE_ADMIN_REQUIRED;
import static com.sweet.referenceapp.user.application.AppUserAdminException.Code.OPTIMISTIC_LOCK_CONFLICT;

import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserAdminService {
    private final AppBootstrapStateRepository states;
    private final AppUserRepository users;
    private final AppAdminTransactionSettings settings;
    private final Clock clock;

    public AppUserAdminService(AppBootstrapStateRepository states, AppUserRepository users,
            AppAdminTransactionSettings settings, Clock clock) {
        this.states = states;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public AppUserView changeStatus(UUID actorId, UUID targetId, AppUserStatus status, long version) {
        validate(actorId, targetId, version);
        if (status == null) {
            throw new AppUserAdminException(INVALID_REQUEST);
        }
        return change(actorId, targetId, version, user -> user.changeStatus(status, clock.instant()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public AppUserView changeRoles(UUID actorId, UUID targetId, Set<AppRole> roles, long version) {
        validate(actorId, targetId, version);
        if (roles == null || roles.stream().anyMatch(role -> role == null)
                || !roles.contains(AppRole.APP_USER)) {
            throw new AppUserAdminException(INVALID_REQUEST);
        }
        var nextRoles = Set.copyOf(roles);
        return change(actorId, targetId, version, user -> user.changeRoles(nextRoles, clock.instant()));
    }

    private void validate(UUID actorId, UUID targetId, long version) {
        if (actorId == null || targetId == null || version < 0) {
            throw new AppUserAdminException(INVALID_REQUEST);
        }
    }

    private AppUserView change(UUID actorId, UUID targetId, long expectedVersion,
            UnaryOperator<AppUser> mutation) {
        settings.apply();
        states.findSingletonForUpdate();
        var actor = users.findByIdForUpdate(actorId)
                .orElseThrow(() -> new AppUserAdminException(FORBIDDEN));
        if (!isActiveAdministrator(actor)) {
            throw new AppUserAdminException(FORBIDDEN);
        }
        var current = actorId.equals(targetId) ? actor : users.findByIdForUpdate(targetId)
                .orElseThrow(() -> new AppUserAdminException(APP_USER_NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new AppUserAdminException(OPTIMISTIC_LOCK_CONFLICT);
        }
        var next = mutation.apply(current);
        if (next == current) {
            return AppUserView.from(current);
        }
        if (isActiveAdministrator(current) && !isActiveAdministrator(next)
                && users.countActiveAdministrators() <= 1) {
            throw new AppUserAdminException(LAST_ACTIVE_ADMIN_REQUIRED);
        }
        return AppUserView.from(users.updateAdministration(next));
    }

    private boolean isActiveAdministrator(AppUser user) {
        return user.status() == AppUserStatus.ACTIVE && user.roles().contains(AppRole.APP_ADMIN);
    }
}
