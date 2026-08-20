package com.sweet.authstudy.shared.error;

import java.sql.SQLException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.sweet.authstudy.shared.trace.TraceIdProvider;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_FIELD_MESSAGE = "Invalid request value.";
    private static final String INTERNAL_ERROR_DETAIL = "The request could not be completed.";
    private final ApiProblemFactory problemFactory;
    private final TraceIdProvider traceIdProvider;

    public GlobalExceptionHandler(ApiProblemFactory problemFactory, TraceIdProvider traceIdProvider) {
        this.problemFactory = problemFactory;
        this.traceIdProvider = traceIdProvider;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), INVALID_FIELD_MESSAGE))
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Validation failed.", fieldErrors, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, "Validation failed.", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return problem(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource was not found.", List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        ProblemDetail body = problem(ErrorCode.METHOD_NOT_ALLOWED,
                "The request method is not supported for this resource.", List.of(), request);
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .headers(exception.getHeaders()).body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        ProblemDetail body = problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "The request media type is not supported.", List.of(), request);
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status())
                .headers(exception.getHeaders()).body(body);
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
        log.warn("API error: {}, traceId={}", errorCode, traceIdProvider.current());
        return problemFactory.create(errorCode, detail, fieldErrors);
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
            case METHOD_NOT_ALLOWED -> "The request method is not supported for this resource.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request media type is not supported.";
            case DUPLICATE_CODE, DUPLICATE_EMAIL, DUPLICATE_EMPLOYEE_NUMBER ->
                    "A resource with the same unique value already exists.";
            case OPTIMISTIC_LOCK_CONFLICT -> "The resource was changed by another request. Retry with the latest version.";
            case INVALID_STATE -> "The request is not valid for the current resource state.";
            case INTERNAL_ERROR -> INTERNAL_ERROR_DETAIL;
        };
    }
}
