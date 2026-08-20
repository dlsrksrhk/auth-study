package com.sweet.authstudy.shared.error;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {
    public ProblemDetail create(
            ErrorCode errorCode, String detail, String traceId, List<FieldViolation> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setType(errorCode.type());
        problem.setTitle(errorCode.title());
        problem.setProperty("code", errorCode.name());
        problem.setProperty("traceId", traceId);
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }
}
