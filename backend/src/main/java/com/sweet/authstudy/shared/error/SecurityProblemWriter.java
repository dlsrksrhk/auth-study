package com.sweet.authstudy.shared.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SecurityProblemWriter {
    private final ApiProblemFactory problemFactory;
    private final ObjectMapper objectMapper;

    public SecurityProblemWriter(ApiProblemFactory problemFactory, ObjectMapper objectMapper) {
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
            ErrorCode errorCode, String detail) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (errorCode == ErrorCode.UNAUTHENTICATED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        String incomingTraceId = request.getHeader("X-Trace-Id");
        String traceId = StringUtils.hasText(incomingTraceId) ? incomingTraceId : UUID.randomUUID().toString();
        objectMapper.writeValue(response.getWriter(),
                problemFactory.create(errorCode, detail, traceId, List.of()));
    }
}
