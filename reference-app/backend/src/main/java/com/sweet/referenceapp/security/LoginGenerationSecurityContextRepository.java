package com.sweet.referenceapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.util.WebUtils;

final class LoginGenerationSecurityContextRepository extends HttpSessionSecurityContextRepository {
    LoginGenerationSecurityContextRepository() {
        setAllowSessionCreation(false);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            RpLoginGeneration.capture(request);
            return super.loadDeferredContext(request);
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            RpLoginGeneration.capture(request);
            var context = super.loadDeferredContext(request);
            // Bind the loaded authentication and request generation in one critical section.
            context.get();
            return context;
        }
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        RpLoginGeneration.withCurrent(request, () -> super.saveContext(context, request, response));
    }
}
