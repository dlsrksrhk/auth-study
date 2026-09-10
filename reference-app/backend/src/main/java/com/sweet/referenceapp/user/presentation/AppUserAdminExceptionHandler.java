package com.sweet.referenceapp.user.presentation;

import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminException.Code;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=AppUserAdminController.class)
public class AppUserAdminExceptionHandler {
    @ExceptionHandler(AppUserAdminException.class)
    public ResponseEntity<ProblemDetail> domain(AppUserAdminException exception,HttpServletRequest request) {
        return problem(exception.code(),request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception exception,HttpServletRequest request) {
        return problem(Code.INVALID_REQUEST,request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> conflict(Exception exception,HttpServletRequest request) {
        return problem(Code.OPTIMISTIC_LOCK_CONFLICT,request);
    }

    @ExceptionHandler({DataAccessException.class,TransactionTimedOutException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception exception,HttpServletRequest request) {
        return problem(Code.SERVICE_UNAVAILABLE,request);
    }

    private ResponseEntity<ProblemDetail> problem(Code code,HttpServletRequest request) {
        var status = switch(code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case APP_USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OPTIMISTIC_LOCK_CONFLICT,LAST_ACTIVE_ADMIN_REQUIRED -> HttpStatus.CONFLICT;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var detail = switch(code) {
            case INVALID_REQUEST -> "The request is invalid.";
            case FORBIDDEN -> "Administrator access is required.";
            case APP_USER_NOT_FOUND -> "The app user was not found.";
            case OPTIMISTIC_LOCK_CONFLICT -> "The app user has changed. Reload and try again.";
            case LAST_ACTIVE_ADMIN_REQUIRED -> "At least one active administrator is required.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable.";
        };
        var problem = ProblemDetail.forStatusAndDetail(status,detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code",code.name());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(problem);
    }
}
