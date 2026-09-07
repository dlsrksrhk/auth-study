package com.sweet.authstudy.shared.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.shared.trace.TraceIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class SecurityProblemWriter {
    private final ApiProblemFactory problemFactory;
    private final ObjectMapper objectMapper;
    private final TraceIdProvider traceIdProvider;

    public SecurityProblemWriter(ApiProblemFactory problemFactory, ObjectMapper objectMapper,
                                 TraceIdProvider traceIdProvider) {
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
        this.traceIdProvider = traceIdProvider;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode, String detail) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(TraceIdProvider.HEADER, traceIdProvider.current());
        if (errorCode == ErrorCode.UNAUTHENTICATED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        objectMapper.writeValue(response.getWriter(),
                problemFactory.create(errorCode, detail, List.of()));
    }
}
