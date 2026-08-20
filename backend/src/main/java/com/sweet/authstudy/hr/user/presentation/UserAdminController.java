package com.sweet.authstudy.hr.user.presentation;

import static com.sweet.authstudy.hr.user.presentation.UserRequests.ChangeUserStatusRequest;
import static com.sweet.authstudy.hr.user.presentation.UserRequests.CreateUserRequest;
import static com.sweet.authstudy.hr.user.presentation.UserRequests.TemporaryPasswordResponse;
import static com.sweet.authstudy.hr.user.presentation.UserRequests.UpdateUserRequest;

import java.util.Set;

import com.sweet.authstudy.hr.user.application.UserCommands.CreateUserCommand;
import com.sweet.authstudy.hr.user.application.UserCommands.UpdateUserCommand;
import com.sweet.authstudy.hr.user.application.UserService;
import com.sweet.authstudy.hr.user.application.UserViews.CreatedUserView;
import com.sweet.authstudy.hr.user.application.UserViews.UserView;
import com.sweet.authstudy.hr.user.domain.UserStatus;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyCode}/users")
public class UserAdminController {
    private static final Set<String> SORTS = Set.of("code", "employeeNumber", "name", "status");
    private final UserService userService;
    private final ActorContext actorContext;
    private final TenantGuard tenantGuard;

    public UserAdminController(UserService userService, ActorContext actorContext, TenantGuard tenantGuard) {
        this.userService = userService;
        this.actorContext = actorContext;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public PageResponse<UserView> list(@PathVariable @ValidCode String companyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status) {
        PageRules.validate(page, size, sort, SORTS);
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        var result = userService.list(actor, companyCode,
                search == null ? "" : search.trim(), status, page, size, sort);
        return new PageResponse<>(result.content(), page, size,
                result.totalElements(), result.totalPages());
    }

    @PostMapping
    public ResponseEntity<CreatedUserView> create(@PathVariable @ValidCode String companyCode,
            @Valid @RequestBody CreateUserRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        CreatedUserView created = userService.create(actor, new CreateUserCommand(
                companyCode, request.code(), request.employeeNumber(), request.name(), request.loginEmail(),
                request.phone(), request.hiredAt(), request.workplace(), request.profileImageUrl(),
                request.positionCode()));
        return ResponseEntity.created(Locations.resource("api", "v1", "admin", "companies",
                BusinessCode.normalize(companyCode), "users", created.user().code())).body(created);
    }

    @GetMapping("/{userCode}")
    public UserView find(@PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return userService.find(actor, companyCode, userCode);
    }

    @PutMapping("/{userCode}")
    public UserView update(@PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode,
            @Valid @RequestBody UpdateUserRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return userService.update(actor, companyCode, userCode, new UpdateUserCommand(
                request.name(), request.phone(), request.hiredAt(), request.workplace(),
                request.profileImageUrl(), request.positionCode(), request.version()));
    }

    @PutMapping("/{userCode}/status")
    public UserView changeStatus(@PathVariable @ValidCode String companyCode,
            @PathVariable @ValidCode String userCode,
            @Valid @RequestBody ChangeUserStatusRequest request) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return userService.changeStatus(actor, companyCode, userCode, request.status(), request.version());
    }

    @PostMapping("/{userCode}/temporary-password")
    public TemporaryPasswordResponse resetTemporaryPassword(
            @PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode) {
        var actor = actorContext.current();
        tenantGuard.requireCompanyAccess(actor, companyCode);
        return new TemporaryPasswordResponse(userService.resetTemporaryPassword(actor, companyCode, userCode));
    }

    @PutMapping("/{userCode}/admin-role")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> assignAdminRole(
            @PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode) {
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        userService.assignCompanyAdmin(actor, companyCode, userCode);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userCode}/admin-role")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> revokeAdminRole(
            @PathVariable @ValidCode String companyCode, @PathVariable @ValidCode String userCode) {
        var actor = actorContext.current();
        tenantGuard.requireSystemAdmin(actor);
        userService.revokeCompanyAdmin(actor, companyCode, userCode);
        return ResponseEntity.noContent().build();
    }
}
