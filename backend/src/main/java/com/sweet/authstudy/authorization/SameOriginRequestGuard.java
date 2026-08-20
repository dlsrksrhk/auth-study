package com.sweet.authstudy.authorization;

import java.io.IOException;

import com.sweet.authstudy.shared.config.AppSecurityProperties;
import com.sweet.authstudy.shared.error.ErrorCode;
import com.sweet.authstudy.shared.error.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

public class SameOriginRequestGuard extends OncePerRequestFilter {
    private final AppSecurityProperties properties;
    private final SecurityProblemWriter problemWriter;
    public SameOriginRequestGuard(AppSecurityProperties properties, SecurityProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (protectedRequest(request) && !properties.browserOrigin().equals(request.getHeader("Origin"))) {
            problemWriter.write(request, response, ErrorCode.FORBIDDEN,
                    "You do not have permission to perform this action.");
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
