package com.sweet.authstudy.audit.infrastructure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sweet.authstudy.audit.domain.AuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_logs")
class AuditLogJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private long actorAccountId;
    @Column(nullable = false, updatable = false)
    private String action;
    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;
    @Column(name = "target_id", nullable = false, updatable = false)
    private long targetId;
    @Column(name = "company_id", updatable = false)
    private Long companyId;
    @Column(nullable = false, updatable = false)
    private boolean success;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details = new LinkedHashMap<>();

    protected AuditLogJpaEntity() {}

    static AuditLogJpaEntity from(AuditLog log) {
        AuditLogJpaEntity entity = new AuditLogJpaEntity();
        entity.actorAccountId = log.actorAccountId();
        entity.action = log.action();
        entity.targetType = log.targetType();
        entity.targetId = log.targetId();
        entity.companyId = log.companyId();
        entity.success = log.success();
        entity.occurredAt = log.occurredAt();
        entity.traceId = log.traceId();
        entity.details = new LinkedHashMap<>(log.details());
        return entity;
    }

    AuditLog toDomain() {
        return new AuditLog(id, actorAccountId, action, targetType, targetId, companyId,
                success, occurredAt, traceId, details);
    }
}
