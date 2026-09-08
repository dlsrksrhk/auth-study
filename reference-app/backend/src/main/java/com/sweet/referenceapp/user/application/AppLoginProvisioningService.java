package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppBootstrapStateRepository;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppLoginProvisioningService {
    private final AppBootstrapStateRepository states;
    private final AppUserProvisioningService provisioning;
    private final AppUserRepository users;
    private final AppAdminBootstrapService bootstrap;

    public AppLoginProvisioningService(AppBootstrapStateRepository states,
            AppUserProvisioningService provisioning, AppUserRepository users,
            AppAdminBootstrapService bootstrap) {
        this.states = states;
        this.provisioning = provisioning;
        this.users = users;
        this.bootstrap = bootstrap;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppUserView provision(ExternalIdentityProfile profile) {
        Objects.requireNonNull(profile, "profile");
        var state = states.findSingletonForUpdate();
        var provisioned = provisioning.provision(profile);
        var current = users.findByIdentityForUpdate(provisioned.issuer(), provisioned.subject())
                .orElseThrow(() -> new IllegalStateException("Provisioned user missing"));
        return AppUserView.from(bootstrap.bootstrap(state, current));
    }
}
