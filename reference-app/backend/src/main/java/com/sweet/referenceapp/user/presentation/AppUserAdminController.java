package com.sweet.referenceapp.user.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweet.referenceapp.security.CurrentAppUser;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminQueryService;
import com.sweet.referenceapp.user.application.AppUserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bff/admin/users")
public class AppUserAdminController {
    private final AppUserAdminService admin;
    private final AppUserAdminQueryService queries;

    public AppUserAdminController(AppUserAdminService admin, AppUserAdminQueryService queries) {
        this.admin = admin;
        this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<AppUserAdminResponses.Page> list(@RequestParam(required=false) String page,
            @RequestParam(required=false) String size, @RequestParam(required=false) String status,
            @RequestParam(required=false) String role) {
        return ok(AppUserAdminResponses.page(queries.list(AppUserAdminRequests.query(page,size,status,role))));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AppUserAdminResponses.Detail> detail(@PathVariable UUID userId) {
        return ok(AppUserAdminResponses.detail(queries.detail(userId)));
    }

    @PutMapping(value="/{userId}/status", consumes="application/json")
    public ResponseEntity<AppUserAdminResponses.Detail> changeStatus(@PathVariable UUID userId,
            @RequestBody JsonNode body, HttpServletRequest request) {
        var input = AppUserAdminRequests.status(body);
        return ok(AppUserAdminResponses.detail(admin.changeStatus(actor(request),userId,input.status(),input.version())));
    }

    @PutMapping(value="/{userId}/roles", consumes="application/json")
    public ResponseEntity<AppUserAdminResponses.Detail> changeRoles(@PathVariable UUID userId,
            @RequestBody JsonNode body, HttpServletRequest request) {
        var input = AppUserAdminRequests.roles(body);
        return ok(AppUserAdminResponses.detail(admin.changeRoles(actor(request),userId,input.roles(),input.version())));
    }

    private UUID actor(HttpServletRequest request) {
        return CurrentAppUser.find(request).orElseThrow(() ->
                new AppUserAdminException(AppUserAdminException.Code.FORBIDDEN)).id();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
