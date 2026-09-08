package com.sweet.referenceapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class BffOriginGuard extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final String allowedOrigin;

    BffOriginGuard(ReferenceSecurityProperties properties) { allowedOrigin = properties.spaOrigin().toString(); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if ((path.equals("/bff") || path.startsWith("/bff/")) && !SAFE_METHODS.contains(request.getMethod())) {
            var origins = Collections.list(request.getHeaders("Origin"));
            if (origins.size() != 1 || !allowedOrigin.equals(origins.getFirst())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
