package com.sweet.authstudy.shared.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "Forbidden"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported-media-type", "Unsupported media type"),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "duplicate-code", "Duplicate code"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "duplicate-email", "Duplicate email"),
    DUPLICATE_EMPLOYEE_NUMBER(HttpStatus.CONFLICT, "duplicate-employee-number", "Duplicate employee number"),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "optimistic-lock-conflict", "Optimistic lock conflict"),
    INVALID_STATE(HttpStatus.CONFLICT, "invalid-state", "Invalid state"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error");

    private final HttpStatus status;
    private final java.net.URI type;
    private final String title;

    ErrorCode(HttpStatus status, String typeSlug, String title) {
        this.status = status;
        this.type = java.net.URI.create("https://auth-study.local/problems/" + typeSlug);
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public java.net.URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
