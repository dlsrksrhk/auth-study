package com.sweet.authstudy.shared.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_CODE(HttpStatus.CONFLICT),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    DUPLICATE_EMPLOYEE_NUMBER(HttpStatus.CONFLICT),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
