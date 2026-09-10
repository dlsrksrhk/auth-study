package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppUserView;
import com.sweet.referenceapp.user.application.CurrentAppUserService;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class CurrentAppUserFilter extends OncePerRequestFilter {
    private final CurrentAppUserService users;
    private final RpSessionCleaner cleaner;

    CurrentAppUserFilter(CurrentAppUserService users, RpSessionCleaner cleaner) {
        this.users = users;
        this.cleaner = cleaner;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/bff/logout/continue/")
                || ("POST".equals(request.getMethod()) && ("/bff/logout".equals(request.getServletPath())
                    || "/bff/logout/identity-provider".equals(request.getServletPath())))
                || !request.getServletPath().startsWith("/bff/")
                || request.getDispatcherType() == DispatcherType.ERROR
                || request.getDispatcherType() == DispatcherType.ASYNC;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        if (!(authentication instanceof OAuth2AuthenticationToken previous)
                || !(previous.getPrincipal() instanceof AppOidcUser principal)) {
            cleaner.clear(request, response);
            chain.doFilter(request, response);
            return;
        }
        Optional<AppUserView> found;
        try {
            found = users.find(principal.localUserId());
        } catch (DataAccessException exception) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (found.isEmpty() || !valid(principal, found.get())) {
            cleaner.clear(request, response);
            chain.doFilter(request, response);
            return;
        }
        var current = found.get();
        var nextPrincipal = principal.withLocalUser(current);
        var next = new OAuth2AuthenticationToken(nextPrincipal, nextPrincipal.getAuthorities(), previous.getAuthorizedClientRegistrationId());
        next.setDetails(previous.getDetails());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(next);
        SecurityContextHolder.setContext(context);
        CurrentAppUser.set(request, current);
        chain.doFilter(request, response);
    }

    private boolean valid(AppOidcUser principal, AppUserView current) {
        return current.status() == AppUserStatus.ACTIVE
                && principal.localUserId().equals(current.id())
                && current.issuer().equals(principal.getIdToken().getClaimAsString("iss"))
                && current.subject().equals(principal.getIdToken().getSubject());
    }
}
