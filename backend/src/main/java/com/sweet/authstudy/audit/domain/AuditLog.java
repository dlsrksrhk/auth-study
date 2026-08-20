package com.sweet.authstudy.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AuditLog(
        Long id,
        long actorAccountId,
        String action,
        String targetType,
        long targetId,
        Long companyId,
        boolean success,
        Instant occurredAt,
        String traceId,
        Map<String, Object> details) {

    public AuditLog {
        Objects.requireNonNull(action);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(traceId);
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
