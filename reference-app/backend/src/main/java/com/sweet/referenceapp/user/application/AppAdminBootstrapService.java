package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppBootstrapState;
import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppAdminBootstrapService {
    private final AppUserRepository users;
    private final AppBootstrapStateRepository states;
    private final Clock clock;

    public AppAdminBootstrapService(AppUserRepository users,
            AppBootstrapStateRepository states, Clock clock) {
        this.users = users;
        this.states = states;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppUser bootstrap(AppBootstrapState state, AppUser current) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(current, "current");
        if (state.completed() || current.status() != AppUserStatus.ACTIVE
                || !current.snapshot().hrRoles().contains("COMPANY_ADMIN")) {
            return current;
        }
        var now = clock.instant();
        var promoted = users.addAdministrator(current, now);
        states.complete(state.complete(promoted.id(), now));
        return promoted;
    }
}
