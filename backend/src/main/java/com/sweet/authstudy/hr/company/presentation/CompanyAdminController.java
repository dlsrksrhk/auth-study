package com.sweet.authstudy.hr.company.presentation;

import static com.sweet.authstudy.hr.company.presentation.CompanyRequests.CreateCompanyRequest;
import static com.sweet.authstudy.hr.company.presentation.CompanyRequests.UpdateCompanyRequest;

import java.util.Set;

import com.sweet.authstudy.hr.company.application.CompanyCommands.CreateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyCommands.UpdateCompanyCommand;
import com.sweet.authstudy.hr.company.application.CompanyService;
import com.sweet.authstudy.hr.company.application.CompanyView;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.presentation.Locations;
import com.sweet.authstudy.shared.security.ActorContext;
import com.sweet.authstudy.shared.security.TenantGuard;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/admin/companies")
public class CompanyAdminController {
    private static final Set<String> SORTS = Set.of("code", "name", "status");
    private final CompanyService companyService;
    private final ActorContext actorContext;
    private final TenantGuard tenantGuard;

    public CompanyAdminController(
            CompanyService companyService, ActorContext actorContext, TenantGuard tenantGuard) {
        this.companyService = companyService;
        this.actorContext = actorContext;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public PageResponse<CompanyView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CompanyStatus status) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        var result = companyService.list(actor,
                search == null ? "" : search.trim(), status, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CompanyView> create(@Valid @RequestBody CreateCompanyRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        CompanyView created = companyService.create(actor,
                new CreateCompanyCommand(request.code(), request.name(), request.emailDomain()));
        return ResponseEntity.created(Locations.resource(
                "api", "v1", "admin", "companies", created.code())).body(created);
    }

    @GetMapping("/{companyCode}")
    public CompanyView find(@PathVariable @ValidCode String companyCode) {
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        return companyService.find(actor, companyCode);
    }

    @PutMapping("/{companyCode}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public CompanyView update(
            @PathVariable @ValidCode String companyCode, @Valid @RequestBody UpdateCompanyRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        return companyService.update(actor, companyCode,
                new UpdateCompanyCommand(request.name(), request.status(), request.version()));
    }
}
