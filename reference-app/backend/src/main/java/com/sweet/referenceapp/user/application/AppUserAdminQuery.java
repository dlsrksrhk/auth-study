package com.sweet.referenceapp.user.application;

import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserStatus;

public record AppUserAdminQuery(int page, int size, AppUserStatus status, AppRole role) {
    public AppUserAdminQuery {
        if (page < 0 || size < 1 || size > 100) {
            throw new AppUserAdminException(AppUserAdminException.Code.INVALID_REQUEST);
        }
    }
}
