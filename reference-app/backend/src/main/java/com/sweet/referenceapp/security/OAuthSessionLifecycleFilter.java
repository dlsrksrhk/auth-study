package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.CurrentAppUserService;
import com.sweet.referenceapp.user.domain.AppUserStatus;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class OAuthSessionLifecycleFilter extends OncePerRequestFilter {
    private final OAuthSessionRefreshCoordinator coordinator;
    private final CurrentAppUserService users;
    private final RpSessionCleaner cleaner;

    OAuthSessionLifecycleFilter(
            OAuthSessionRefreshCoordinator coordinator,
            CurrentAppUserService users,
            RpSessionCleaner cleaner) {
        this.coordinator = coordinator;
        this.users = users;
        this.cleaner = cleaner;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return request.getDispatcherType() != DispatcherType.REQUEST
                || !path.startsWith("/bff/")
                || path.equals("/bff/login")
                || path.equals("/bff/csrf")
                || path.equals("/bff/logout")
                || path.equals("/bff/logout/identity-provider")
                || path.startsWith("/bff/logout/continue/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken previous
                && previous.getPrincipal() instanceof AppOidcUser principal) {
            try {
                if (coordinator.ensureFresh(request, response, previous).isPresent()) {
                    var current =
                            users.find(principal.localUserId())
                                    .orElseThrow(
                                            OAuthSessionRefreshCoordinator.SessionRefreshException
                                                    ::new);
                    if (current.status() != AppUserStatus.ACTIVE
                            || !current.id().equals(principal.localUserId())
                            || !current.issuer()
                                    .equals(principal.getIdToken().getClaimAsString("iss"))
                            || !current.subject().equals(principal.getIdToken().getSubject())) {
                        throw new OAuthSessionRefreshCoordinator.SessionRefreshException();
                    }
                    var nextPrincipal = principal.withLocalUser(current);
                    var next =
                            new OAuth2AuthenticationToken(
                                    nextPrincipal,
                                    nextPrincipal.getAuthorities(),
                                    previous.getAuthorizedClientRegistrationId());
                    next.setDetails(previous.getDetails());
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(next);
                    SecurityContextHolder.setContext(context);
                    CurrentAppUser.set(request, current);
                }
            } catch (RuntimeException failure) {
                cleaner.clear(request, response);
                if (!"/bff/session".equals(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
