package com.sweet.referenceapp.user.application;

public class AppUserAdminException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST, FORBIDDEN, APP_USER_NOT_FOUND, OPTIMISTIC_LOCK_CONFLICT,
        LAST_ACTIVE_ADMIN_REQUIRED, SERVICE_UNAVAILABLE
    }

    private final Code code;

    public AppUserAdminException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
