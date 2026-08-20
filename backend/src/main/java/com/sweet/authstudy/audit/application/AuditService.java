package com.sweet.authstudy.audit.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.sweet.authstudy.audit.domain.AuditLog;
import com.sweet.authstudy.audit.domain.AuditLogRepository;
import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.shared.application.PageResult;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.trace.TraceIdProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final Set<String> SAFE_DETAIL_KEYS = Set.of(
            "code", "status", "previousStatus", "active", "previousActive", "role",
            "primary", "parentCode", "departmentCode", "userCode", "positionCode",
            "membershipId", "errorCode");

    private final AuditLogRepository repository;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final TraceIdProvider traceIdProvider;

    public AuditService(AuditLogRepository repository, TenantGuard tenantGuard,
            Clock clock, TraceIdProvider traceIdProvider) {
        this.repository = repository;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
        this.traceIdProvider = traceIdProvider;
    }

    @Transactional
    public AuditView record(AuditCommand command) {
        return save(command);
    }

    @Transactional
    public AuditView record(AuthenticatedAccount actor, String action, String targetType,
            long targetId, Long companyId, Map<String, Object> safeDetails) {
        return save(new AuditCommand(actor.accountId(), action, targetType, targetId,
                companyId, true, traceIdProvider.current(), safeDetails));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditView recordFailure(AuditCommand command) {
        return save(new AuditCommand(command.actorAccountId(), command.action(), command.targetType(),
                command.targetId(), command.companyId(), false, command.traceId(), command.safeDetails()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditView recordFailure(AuthenticatedAccount actor, String action, String targetType,
            long targetId, Long companyId, Map<String, Object> safeDetails, ApiException failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (safeDetails != null) details.putAll(safeDetails);
        details.put("errorCode", failure.errorCode().name());
        return save(new AuditCommand(actor.accountId(), action, targetType, targetId,
                companyId, false, traceIdProvider.current(), details));
    }

    @Transactional(readOnly = true)
    public PageResult<AuditView> list(AuthenticatedAccount actor, String companyCode,
            String action, Boolean success, int page, int size, String sort) {
        return list(actor, companyCode, "", action, success, page, size, sort);
    }

    @Transactional(readOnly = true)
    public PageResult<AuditView> list(AuthenticatedAccount actor, String companyCode, String search,
            String action, Boolean success, int page, int size, String sort) {
        long companyId = tenantGuard.requireCompanyAccess(actor, companyCode);
        var result = repository.search(companyId, search, action, success, page, size, sort);
        return new PageResult<>(result.content().stream().map(AuditView::from).toList(),
                result.totalElements(), result.totalPages());
    }

    private AuditView save(AuditCommand command) {
        Map<String, Object> details = sanitize(command.safeDetails());
        return AuditView.from(repository.save(new AuditLog(null, command.actorAccountId(),
                command.action(), command.targetType(), command.targetId(), command.companyId(),
                command.success(), clock.instant(), command.traceId(), details)));
    }

    private Map<String, Object> sanitize(Map<String, Object> supplied) {
        if (supplied == null || supplied.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        supplied.forEach((key, value) -> {
            if (SAFE_DETAIL_KEYS.contains(key) && scalar(value)) safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    private boolean scalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
