package com.sweet.authstudy.hr.department.presentation;

import static com.sweet.authstudy.hr.department.presentation.DepartmentRequests.CreateDepartmentRequest;
import static com.sweet.authstudy.hr.department.presentation.DepartmentRequests.UpdateDepartmentRequest;

import java.util.Set;

import com.sweet.authstudy.hr.department.application.DepartmentCommands.CreateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentCommands.UpdateDepartmentCommand;
import com.sweet.authstudy.hr.department.application.DepartmentService;
import com.sweet.authstudy.hr.department.application.DepartmentView;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.presentation.Locations;
import com.sweet.authstudy.shared.security.ActorContext;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import com.sweet.authstudy.shared.validation.ValidCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/departments")
public class DepartmentAdminController {
    private static final Set<String> SORTS = Set.of("code", "name", "status");
    private final DepartmentService departmentService;
    private final ActorContext actorContext;
    private final TenantGuard tenantGuard;

    public DepartmentAdminController(
            DepartmentService departmentService, ActorContext actorContext, TenantGuard tenantGuard) {
        this.departmentService = departmentService;
        this.actorContext = actorContext;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public PageResponse<DepartmentView> list(@PathVariable @ValidCode String companyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) DepartmentStatus status) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        var result = departmentService.search(actor, companyCode,
                search == null ? "" : search.trim(), status, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }

    @PostMapping
    public ResponseEntity<DepartmentView> create(@PathVariable @ValidCode String companyCode,
            @Valid @RequestBody CreateDepartmentRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        DepartmentView created = departmentService.create(actor,
                new CreateDepartmentCommand(companyCode, request.code(), request.name(), request.parentCode()));
        return ResponseEntity.created(Locations.resource("api", "v1", "admin", "companies",
                BusinessCode.normalize(companyCode), "departments", created.code())).body(created);
    }

    @PutMapping("/{departmentCode}")
    public DepartmentView update(@PathVariable @ValidCode String companyCode,
            @PathVariable @ValidCode String departmentCode,
            @Valid @RequestBody UpdateDepartmentRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return departmentService.update(actor, new UpdateDepartmentCommand(
                companyCode, departmentCode, request.name(), request.parentCode(),
                request.status(), request.version()));
    }
}
