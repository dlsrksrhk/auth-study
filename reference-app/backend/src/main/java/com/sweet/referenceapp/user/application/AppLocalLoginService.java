package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppUserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppLocalLoginService {
    private final AppLoginProvisioningService provisioning;

    public AppLocalLoginService(AppLoginProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppUserView login(ExternalIdentityProfile profile) {
        var user = provisioning.provision(profile);
        if (user.status() != AppUserStatus.ACTIVE) {
            throw new LocalUserDisabledException();
        }
        return user;
    }
}
