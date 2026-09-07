package com.sweet.authstudy.audit.application;

import com.sweet.authstudy.audit.domain.AuditLog;

import java.time.Instant;
import java.util.Map;

public record AuditView(
        long id,
        long actorAccountId,
        String action,
        String targetType,
        long targetId,
        Long companyId,
        boolean success,
        Instant occurredAt,
        String traceId,
        Map<String, Object> details) {

    public static AuditView from(AuditLog log) {
        return new AuditView(log.id(), log.actorAccountId(), log.action(), log.targetType(),
                log.targetId(), log.companyId(), log.success(), log.occurredAt(),
                log.traceId(), log.details());
    }
}
