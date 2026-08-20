package com.sweet.authstudy.hr.position.presentation;

import static com.sweet.authstudy.hr.position.presentation.PositionRequests.CreatePositionRequest;
import static com.sweet.authstudy.hr.position.presentation.PositionRequests.UpdatePositionRequest;

import java.util.Set;

import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.position.application.PositionView;
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
@RequestMapping("/api/v1/admin/companies/{companyCode}/positions")
public class PositionAdminController {
    private static final Set<String> SORTS = Set.of("code", "name", "level", "displayOrder");
    private final PositionService positionService;
    private final ActorContext actorContext;
    private final TenantGuard tenantGuard;

    public PositionAdminController(
            PositionService positionService, ActorContext actorContext, TenantGuard tenantGuard) {
        this.positionService = positionService;
        this.actorContext = actorContext;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public PageResponse<PositionView> list(
            @PathVariable @ValidCode String companyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "displayOrder") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        var result = positionService.search(actor, companyCode,
                search == null ? "" : search.trim(), active, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }

    @PostMapping
    public ResponseEntity<PositionView> create(
            @PathVariable @ValidCode String companyCode, @Valid @RequestBody CreatePositionRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        PositionView created = positionService.create(actor, companyCode,
                new CreatePositionCommand(request.code(), request.name(), request.level(), request.displayOrder()));
        return ResponseEntity.created(Locations.resource("api", "v1", "admin", "companies",
                BusinessCode.normalize(companyCode), "positions", created.code())).body(created);
    }

    @PutMapping("/{positionCode}")
    public PositionView update(@PathVariable @ValidCode String companyCode,
            @PathVariable @ValidCode String positionCode,
            @Valid @RequestBody UpdatePositionRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return positionService.update(actor, companyCode, positionCode,
                new UpdatePositionCommand(request.name(), request.level(), request.displayOrder(),
                        request.active(), request.version()));
    }
}
