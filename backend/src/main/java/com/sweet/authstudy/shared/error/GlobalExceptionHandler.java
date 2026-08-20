package com.sweet.authstudy.shared.error;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";
    private static final String INVALID_FIELD_MESSAGE = "Invalid request value.";
    private static final String INTERNAL_ERROR_DETAIL = "The request could not be completed.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), INVALID_FIELD_MESSAGE))
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Validation failed.", fieldErrors, request);
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception, HttpServletRequest request) {
        return problem(exception.errorCode(), publicDetail(exception.errorCode()), List.of(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return problem(
                ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "The resource was changed by another request. Retry with the latest version.",
                List.of(),
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        if (!isUniqueConstraintViolation(exception)) {
            return problem(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, List.of(), request);
        }
        return problem(
                duplicateErrorCode(exception),
                "A resource with the same unique value already exists.",
                List.of(),
                request);
    }

    private ProblemDetail problem(
            ErrorCode errorCode,
            String detail,
            List<FieldViolation> fieldErrors,
            HttpServletRequest request) {
        String traceId = traceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        try {
            log.warn("API error: {}", errorCode);
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
            problemDetail.setProperty("code", errorCode.name());
            problemDetail.setProperty("traceId", traceId);
            problemDetail.setProperty("fieldErrors", fieldErrors);
            return problemDetail;
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String traceId(HttpServletRequest request) {
        String requestTraceId = request.getHeader(TRACE_ID_HEADER);
        return StringUtils.hasText(requestTraceId) ? requestTraceId : UUID.randomUUID().toString();
    }

    private ErrorCode duplicateErrorCode(DataIntegrityViolationException exception) {
        String constraintName = findConstraintName(exception);
        if (constraintName.contains("email")) {
            return ErrorCode.DUPLICATE_EMAIL;
        }
        if (constraintName.contains("employee_number")) {
            return ErrorCode.DUPLICATE_EMPLOYEE_NUMBER;
        }
        return ErrorCode.DUPLICATE_CODE;
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String findConstraintName(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                return StringUtils.hasText(constraintViolation.getConstraintName())
                        ? constraintViolation.getConstraintName().toLowerCase()
                        : "";
            }
            current = current.getCause();
        }
        return "";
    }

    private String publicDetail(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_FAILED -> "The request contains invalid values.";
            case UNAUTHENTICATED -> "Authentication is required.";
            case FORBIDDEN -> "You do not have permission to perform this action.";
            case RESOURCE_NOT_FOUND -> "The requested resource was not found.";
            case DUPLICATE_CODE, DUPLICATE_EMAIL, DUPLICATE_EMPLOYEE_NUMBER ->
                    "A resource with the same unique value already exists.";
            case OPTIMISTIC_LOCK_CONFLICT -> "The resource was changed by another request. Retry with the latest version.";
            case INVALID_STATE -> "The request is not valid for the current resource state.";
            case INTERNAL_ERROR -> INTERNAL_ERROR_DETAIL;
        };
    }
}
