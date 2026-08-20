package com.sweet.authstudy.hr.membership.presentation;

import static com.sweet.authstudy.hr.membership.presentation.MembershipRequests.AssignMembershipRequest;
import static com.sweet.authstudy.hr.membership.presentation.MembershipRequests.UpdateMembershipRequest;

import java.util.Set;

import com.sweet.authstudy.hr.membership.application.MembershipCommands.AssignMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipCommands.UpdateMembershipCommand;
import com.sweet.authstudy.hr.membership.application.MembershipService;
import com.sweet.authstudy.hr.membership.application.MembershipView;
import com.sweet.authstudy.shared.presentation.PageResponse;
import com.sweet.authstudy.shared.presentation.PageRules;
import com.sweet.authstudy.shared.presentation.Locations;
import com.sweet.authstudy.shared.security.ActorContext;
import com.sweet.authstudy.shared.security.TenantGuard;
import com.sweet.authstudy.shared.validation.BusinessCode;
import com.sweet.authstudy.shared.validation.ValidCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/users/{userCode}/memberships")
public class MembershipAdminController {
    private static final Set<String> SORTS = Set.of("id", "departmentId", "role", "startedAt");
    private final MembershipService membershipService;
    private final ActorContext actorContext;
    private final TenantGuard tenantGuard;

    public MembershipAdminController(
            MembershipService membershipService, ActorContext actorContext, TenantGuard tenantGuard) {
        this.membershipService = membershipService;
        this.actorContext = actorContext;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public PageResponse<MembershipView> list(
            @PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startedAt") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) MembershipStatus status) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        Boolean active = status == null ? null : status == MembershipStatus.ACTIVE;
        var result = membershipService.searchByUser(actor, companyCode, userCode,
                search == null ? "" : search.trim(), active, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }

    @PostMapping
    public ResponseEntity<MembershipView> assign(
            @PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode,
            @Valid @RequestBody AssignMembershipRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        MembershipView created = membershipService.assign(actor, new AssignMembershipCommand(
                companyCode, userCode, request.departmentCode(), request.role(),
                request.primary(), request.startedAt()));
        return ResponseEntity.created(Locations.resource("api", "v1", "admin", "companies",
                BusinessCode.normalize(companyCode), "users", BusinessCode.normalize(userCode),
                "memberships", created.id().toString())).body(created);
    }

    @PutMapping("/{membershipId}")
    public MembershipView update(@PathVariable @ValidCode String companyCode,
            @PathVariable @ValidCode String userCode,
            @PathVariable long membershipId, @Valid @RequestBody UpdateMembershipRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return membershipService.update(actor, new UpdateMembershipCommand(
                companyCode, userCode, membershipId, request.role(), request.primary(), request.version()));
    }

    @DeleteMapping("/{membershipId}")
    public MembershipView end(@PathVariable @ValidCode String companyCode,
            @PathVariable @ValidCode String userCode,
            @PathVariable long membershipId, @RequestParam Long version) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return membershipService.end(actor, companyCode, userCode, membershipId, version);
    }

    public enum MembershipStatus { ACTIVE, ENDED }
}
