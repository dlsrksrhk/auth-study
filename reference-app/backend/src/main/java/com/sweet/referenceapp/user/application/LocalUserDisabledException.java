package com.sweet.referenceapp.user.application;

public class LocalUserDisabledException extends RuntimeException {
    public LocalUserDisabledException() {
        super("Local user is disabled");
    }
}
