package com.sweet.authstudy.shared.error;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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
    private static final Pattern SECRET_REFERENCE = Pattern.compile("(?i)\\b(password|token)\\b");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Validation failed.", fieldErrors, request);
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception, HttpServletRequest request) {
        return problem(exception.errorCode(), safeDetail(exception.getMessage()), List.of(), request);
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

    private String safeDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return "The request could not be completed.";
        }
        if (SECRET_REFERENCE.matcher(detail).find()) {
            return "The request could not be completed.";
        }
        return detail;
    }
}
