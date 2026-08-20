package com.sweet.authstudy.shared.trace;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class TraceIdProvider {
    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceIdProvider.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    public String initialize(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        String traceId = StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object traceId = attributes.getRequest().getAttribute(ATTRIBUTE);
            if (traceId instanceof String value && StringUtils.hasText(value)) return value;
        }
        String traceId = MDC.get(MDC_KEY);
        return StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
    }

    public void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
        MDC.remove(MDC_KEY);
    }
}
