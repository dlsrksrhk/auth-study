package com.sweet.referenceapp.user.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ExternalUserSnapshot(
        String email,
        String displayName,
        Map<String, Object> company,
        Map<String, Object> organization,
        Set<String> hrRoles) {

    private static final Set<String> CODE_NAME_KEYS = Set.of("code", "name");
    private static final Set<String> ORGANIZATION_KEYS = Set.of(
            "position", "primary_department", "secondary_departments");

    public ExternalUserSnapshot {
        company = copyCodeName(company);
        organization = copyOrganization(organization);
        hrRoles = copyRoles(hrRoles);
    }

    private static Map<String, Object> copyCodeName(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        requireKeys(value, CODE_NAME_KEYS);
        if (!value.keySet().containsAll(CODE_NAME_KEYS)) {
            throw new IllegalArgumentException("Snapshot code and name are required");
        }
        requireNonBlankString(value.get("code"));
        requireNonBlankString(value.get("name"));
        return Map.copyOf(value);
    }

    private static Map<String, Object> copyOrganization(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        requireKeys(value, ORGANIZATION_KEYS);
        var copy = new LinkedHashMap<String, Object>();
        if (value.containsKey("position")) {
            copy.put("position", requireCodeName(value.get("position")));
        }
        if (value.containsKey("primary_department")) {
            copy.put("primary_department", requireCodeName(value.get("primary_department")));
        }
        copy.put("secondary_departments", copyDepartments(value.get("secondary_departments")));
        return Map.copyOf(copy);
    }

    private static Map<String, Object> requireCodeName(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Snapshot field has invalid type");
        }
        var typed = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Snapshot field has invalid type");
            }
            typed.put(key, entry.getValue());
        }
        return copyCodeName(typed);
    }

    private static List<Map<String, Object>> copyDepartments(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Snapshot field has invalid type");
        }
        var copy = new ArrayList<Map<String, Object>>(list.size());
        for (var department : list) {
            copy.add(requireCodeName(department));
        }
        return List.copyOf(copy);
    }

    private static Set<String> copyRoles(Set<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        for (var role : roles) {
            requireNonBlankString(role);
        }
        return Set.copyOf(roles);
    }

    private static void requireKeys(Map<String, Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) {
            throw new IllegalArgumentException("Unsupported snapshot field");
        }
    }

    private static void requireNonBlankString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Snapshot field must be a nonblank string");
        }
    }
}
