package com.sweet.referenceapp.user.presentation;

import com.sweet.referenceapp.user.application.AppUserAdminPage;
import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AppUserAdminResponses {
    private AppUserAdminResponses() {}

    public record Summary(UUID id, String displayName, String email, AppUserStatus status,
            List<AppRole> roles, String lastLoginAt, long version) {}
    public record ExternalIdentity(String issuer, String subject) {}
    public record Detail(UUID id, String displayName, String email, AppUserStatus status,
            List<AppRole> roles, String lastLoginAt, long version, String createdAt, String updatedAt,
            ExternalIdentity externalIdentity, Map<String,Object> company,
            Map<String,Object> organization, List<String> hrRoles) {}
    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {}

    public static Detail detail(AppUserView user) {
        var snapshot = user.snapshot();
        return new Detail(user.id(), snapshot.displayName(), snapshot.email(), user.status(), roles(user),
                iso(user.lastLoginAt()), user.version(), iso(user.createdAt()), iso(user.updatedAt()),
                new ExternalIdentity(user.issuer(),user.subject()), snapshot.company(), snapshot.organization(),
                snapshot.hrRoles().stream().sorted().toList());
    }

    public static Page page(AppUserAdminPage page) {
        return new Page(page.items().stream().map(AppUserAdminResponses::summary).toList(),
                page.page(),page.size(),page.totalElements(),page.totalPages());
    }

    private static Summary summary(AppUserView user) {
        return new Summary(user.id(), user.snapshot().displayName(),user.snapshot().email(),user.status(),
                roles(user),iso(user.lastLoginAt()),user.version());
    }

    private static List<AppRole> roles(AppUserView user) {
        return List.of(AppRole.APP_USER,AppRole.APP_ADMIN).stream().filter(user.roles()::contains).toList();
    }

    private static String iso(Instant instant) { return instant == null ? null : instant.toString(); }
}
