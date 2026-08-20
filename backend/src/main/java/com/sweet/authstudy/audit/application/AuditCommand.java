package com.sweet.authstudy.audit.application;

import java.util.Map;

public record AuditCommand(
        long actorAccountId,
        String action,
        String targetType,
        long targetId,
        Long companyId,
        boolean success,
        String traceId,
        Map<String, Object> safeDetails) {
}
