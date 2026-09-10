package com.sweet.referenceapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminApiHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/bff/admin") || path.startsWith("/bff/admin/")) {
            response.setHeader("Cache-Control","no-store");
        }
        chain.doFilter(request,response);
    }
}
