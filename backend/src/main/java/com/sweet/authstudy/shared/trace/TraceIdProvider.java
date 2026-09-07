package com.sweet.authstudy.shared.trace;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Component
public class TraceIdProvider {
    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceIdProvider.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    public String initialize(HttpServletRequest request) {
        // Correlation identifiers are server-owned. An untrusted header may contain a credential
        // or response-splitting data, so it is never copied into MDC, logs, or persistence.
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object traceId = attributes.getRequest().getAttribute(ATTRIBUTE);
            if (traceId instanceof String value && !value.isBlank()) return value;
        }
        String traceId = MDC.get(MDC_KEY);
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }

    public void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
        MDC.remove(MDC_KEY);
    }
}
