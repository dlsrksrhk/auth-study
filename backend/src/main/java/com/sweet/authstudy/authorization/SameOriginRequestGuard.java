package com.sweet.authstudy.authorization;

import java.io.IOException;

import com.sweet.authstudy.shared.config.AppSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

public class SameOriginRequestGuard extends OncePerRequestFilter {
    private final AppSecurityProperties properties;
    public SameOriginRequestGuard(AppSecurityProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (protectedRequest(request) && !properties.browserOrigin().equals(request.getHeader("Origin"))) {
            SecurityConfig.writeSecurityProblem(response, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", "You do not have permission to perform this action.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean protectedRequest(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) return false;
        return request.getRequestURI().equals("/api/v1/auth/refresh")
                || request.getRequestURI().equals("/api/v1/auth/logout");
    }
}
