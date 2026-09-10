package com.sweet.referenceapp.user.application;

import java.util.List;

public record AppUserAdminPage(List<AppUserView> items, int page, int size,
        long totalElements, long totalPages) {
    public AppUserAdminPage {
        items = List.copyOf(items);
    }
}
