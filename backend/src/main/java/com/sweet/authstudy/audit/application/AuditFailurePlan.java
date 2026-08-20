package com.sweet.authstudy.audit.application;

import java.util.Map;

import com.sweet.authstudy.authorization.AuthenticatedAccount;

public final class AuditFailurePlan {
    private AuthenticatedAccount actor;
    private String action;
    private String targetType;
    private long targetId;
    private Long companyId;
    private Map<String, Object> safeDetails = Map.of();

    public void identify(AuthenticatedAccount actor, String action, String targetType,
            long targetId, Long companyId, Map<String, Object> safeDetails) {
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.companyId = companyId;
        this.safeDetails = safeDetails == null ? Map.of() : Map.copyOf(safeDetails);
    }

    boolean identified() {
        return actor != null;
    }

    AuthenticatedAccount actor() { return actor; }
    String action() { return action; }
    String targetType() { return targetType; }
    long targetId() { return targetId; }
    Long companyId() { return companyId; }
    Map<String, Object> safeDetails() { return safeDetails; }
}
