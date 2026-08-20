package com.sweet.authstudy.hr.position.presentation;

import static com.sweet.authstudy.hr.position.presentation.PositionRequests.CreatePositionRequest;
import static com.sweet.authstudy.hr.position.presentation.PositionRequests.UpdatePositionRequest;

import java.net.URI;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

import com.sweet.authstudy.hr.position.application.PositionCommands.CreatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionCommands.UpdatePositionCommand;
import com.sweet.authstudy.hr.position.application.PositionService;
import com.sweet.authstudy.hr.position.application.PositionView;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.security.ActorContext;
import com.sweet.authstudy.shared.security.TenantGuard;
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
            @PathVariable String companyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "displayOrder") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Comparator<PositionView> comparator = switch (sort) {
            case "name" -> Comparator.comparing(PositionView::name, String.CASE_INSENSITIVE_ORDER);
            case "level" -> Comparator.comparingInt(PositionView::level);
            case "displayOrder" -> Comparator.comparingInt(PositionView::displayOrder);
            default -> Comparator.comparing(PositionView::code);
        };
        var values = positionService.list(actor, companyCode).stream()
                .filter(value -> active == null || value.active() == active)
                .filter(value -> term.isEmpty()
                        || value.code().toLowerCase(Locale.ROOT).contains(term)
                        || value.name().toLowerCase(Locale.ROOT).contains(term))
                .sorted(comparator).toList();
        return PageResponse.of(values, page, size);
    }

    @PostMapping
    public ResponseEntity<PositionView> create(
            @PathVariable String companyCode, @Valid @RequestBody CreatePositionRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        PositionView created = positionService.create(actor, companyCode,
                new CreatePositionCommand(request.code(), request.name(), request.level(), request.displayOrder()));
        return ResponseEntity.created(URI.create("/api/v1/admin/companies/" + companyCode
                + "/positions/" + created.code())).body(created);
    }

    @PutMapping("/{positionCode}")
    public PositionView update(@PathVariable String companyCode, @PathVariable String positionCode,
            @Valid @RequestBody UpdatePositionRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return positionService.update(actor, companyCode, positionCode,
                new UpdatePositionCommand(request.name(), request.level(), request.displayOrder(),
                        request.active(), request.version()));
    }
}
