package com.sweet.authstudy.shared.error;

import java.util.List;

import com.sweet.authstudy.shared.trace.TraceIdProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {
    private final TraceIdProvider traceIdProvider;

    public ApiProblemFactory(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    public ProblemDetail create(
            ErrorCode errorCode, String detail, List<FieldViolation> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setType(errorCode.type());
        problem.setTitle(errorCode.title());
        problem.setProperty("code", errorCode.name());
        problem.setProperty("traceId", traceIdProvider.current());
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }
}
