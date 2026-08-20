package com.sweet.authstudy.audit.presentation;

import java.util.Set;

import com.sweet.authstudy.audit.application.AuditService;
import com.sweet.authstudy.audit.application.AuditView;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.security.ActorContext;
import com.sweet.authstudy.shared.validation.ValidCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/audit-logs")
public class AuditAdminController {
    private static final Set<String> SORTS = Set.of(
            "occurredAt", "action", "success", "actorAccountId", "targetType");
    private final AuditService auditService;
    private final ActorContext actorContext;

    public AuditAdminController(AuditService auditService, ActorContext actorContext) {
        this.auditService = auditService;
        this.actorContext = actorContext;
    }

    @GetMapping
    public PageResponse<AuditView> list(
            @PathVariable @ValidCode String companyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "occurredAt") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean success) {
        PageRules.validate(page, size, sort, SORTS);
        var result = auditService.list(actorContext.current(), companyCode,
                search == null ? "" : search.trim(), action, success, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }
}
