package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppUserView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

public final class CurrentAppUser {
    private CurrentAppUser() {}

    public static void set(HttpServletRequest request, AppUserView user) {
        request.setAttribute(CurrentAppUser.class.getName(), user);
    }

    public static Optional<AppUserView> find(HttpServletRequest request) {
        return Optional.ofNullable((AppUserView) request.getAttribute(CurrentAppUser.class.getName()));
    }
}
