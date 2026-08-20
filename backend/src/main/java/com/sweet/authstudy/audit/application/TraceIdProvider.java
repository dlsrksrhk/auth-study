package com.sweet.authstudy.audit.application;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class TraceIdProvider {
    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    public String current() {
        String traceId = MDC.get(MDC_KEY);
        if (StringUtils.hasText(traceId)) return traceId;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            traceId = attributes.getRequest().getHeader(HEADER);
            if (StringUtils.hasText(traceId)) return traceId;
        }
        return UUID.randomUUID().toString();
    }
}
