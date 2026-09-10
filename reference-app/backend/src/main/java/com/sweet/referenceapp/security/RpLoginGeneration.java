package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.util.WebUtils;

/** Token-free ownership of one login, independent of mutable servlet session IDs. */
final class RpLoginGeneration {
    private static final String KEY = RpLoginGeneration.class.getName();
    private static final String LOGIN = KEY + ".LOGIN";
    private record Binding(HttpSession session, Object generation) {}

    static void capture(HttpServletRequest request) {
        if (request.getAttribute(KEY) != null) return;
        var session = request.getSession(false);
        if (session == null) {
            request.setAttribute(KEY, new Binding(null, null));
            return;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            Object generation = session.getAttribute(KEY);
            if (generation == null) {
                generation = new Object();
                session.setAttribute(KEY, generation);
            }
            request.setAttribute(KEY, new Binding(session, generation));
        }
    }

    // Call under the session mutex. Missing bindings support callers without a security filter.
    static boolean matches(HttpServletRequest request, HttpSession session) {
        capture(request);
        var binding = (Binding) request.getAttribute(KEY);
        return binding.session == session && (session == null || binding.generation == session.getAttribute(KEY));
    }

    static void beginLogin(HttpServletRequest request) {
        withCurrent(request, () -> {
            advance(request);
            request.setAttribute(LOGIN, Boolean.TRUE);
        });
    }

    static boolean isLogin(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(LOGIN));
    }

    static void publishLogin(HttpServletRequest request, Runnable publication) {
        withCurrent(request, () -> {
            // Also fence requests that loaded the old authentication during callback network work.
            advance(request);
            publication.run();
            request.removeAttribute(LOGIN);
        });
    }

    private static void advance(HttpServletRequest request) {
        var session = request.getSession(false);
        OAuthSessionRefreshCoordinator.resetForLogin(session);
        var generation = new Object();
        session.setAttribute(KEY, generation);
        request.setAttribute(KEY, new Binding(session, generation));
    }

    static void withCurrent(HttpServletRequest request, Runnable action) {
        var session = request.getSession(false);
        if (session == null) throw new OAuthSessionRefreshCoordinator.SessionRefreshException();
        synchronized (WebUtils.getSessionMutex(session)) {
            if (!matches(request, session) || OAuthSessionRefreshCoordinator.isClosing(session))
                throw new OAuthSessionRefreshCoordinator.SessionRefreshException();
            action.run();
        }
    }
}
