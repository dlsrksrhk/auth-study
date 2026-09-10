package com.sweet.referenceapp.user.domain;

import java.util.List;

public record AppUserPage(List<AppUser> items, long totalElements) {
    public AppUserPage {
        items = List.copyOf(items);
    }
}
