package com.sweet.referenceapp.user.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweet.referenceapp.user.application.AppUserAdminException;
import com.sweet.referenceapp.user.application.AppUserAdminQuery;
import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import java.util.EnumSet;
import java.util.Set;

public final class AppUserAdminRequests {
    private AppUserAdminRequests() {}
    public record StatusChange(AppUserStatus status, long version) {}
    public record RolesChange(Set<AppRole> roles, long version) {
        public RolesChange { roles = Set.copyOf(roles); }
    }

    public static StatusChange status(JsonNode body) {
        long version = version(body);
        return new StatusChange(enumValue(AppUserStatus.class, text(body.get("status"))), version);
    }

    public static RolesChange roles(JsonNode body) {
        long version = version(body);
        var values = body.get("roles");
        if (values == null || !values.isArray()) throw invalid();
        var roles = EnumSet.noneOf(AppRole.class);
        for (var value : values) roles.add(enumValue(AppRole.class, text(value)));
        if (!roles.contains(AppRole.APP_USER)) throw invalid();
        return new RolesChange(roles, version);
    }

    public static AppUserAdminQuery query(String page, String size, String status, String role) {
        return new AppUserAdminQuery(number(page, 0), number(size, 20),
                status == null ? null : enumValue(AppUserStatus.class, status),
                role == null ? null : enumValue(AppRole.class, role));
    }

    private static long version(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        var value = body.get("version");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]+")) throw invalid();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw invalid(); }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException ex) { throw invalid(); }
    }

    private static AppUserAdminException invalid() {
        return new AppUserAdminException(AppUserAdminException.Code.INVALID_REQUEST);
    }
}
