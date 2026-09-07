package com.sweet.authstudy.oauth.presentation;

import com.sweet.authstudy.identity.domain.AccountRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal IdP browser principal. Its {@link #getName()} deliberately adapts the session identity
 * to Task 7's positive numeric principal-name persistence contract; the OIDC subject remains opaque.
 */
public final class IdpSessionAuthentication implements Authentication {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long accountId;
    private final Long companyId;
    private final Long userId;
    private final Set<AccountRole> roles;
    private final UUID sub;
    private final Instant authenticatedAt;
    private final UUID sessionBinding;

    public IdpSessionAuthentication(long accountId, Long companyId, Long userId,
                                    Set<AccountRole> roles, UUID sub, Instant authenticatedAt) {
        this(accountId, companyId, userId, roles, sub, authenticatedAt, UUID.randomUUID());
    }

    public IdpSessionAuthentication(long accountId, Long companyId, Long userId,
                                    Set<AccountRole> roles, UUID sub, Instant authenticatedAt, UUID sessionBinding) {
        if (accountId <= 0) throw new IllegalArgumentException("accountId must be positive.");
        this.accountId = accountId;
        this.companyId = companyId;
        this.userId = userId;
        this.roles = Set.copyOf(roles);
        this.sub = java.util.Objects.requireNonNull(sub, "sub");
        this.authenticatedAt = java.util.Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        this.sessionBinding = java.util.Objects.requireNonNull(sessionBinding, "sessionBinding");
    }

    public long accountId() {
        return accountId;
    }

    public Long companyId() {
        return companyId;
    }

    public Long userId() {
        return userId;
    }

    public Set<AccountRole> roles() {
        return roles;
    }

    public UUID sub() {
        return sub;
    }

    public Instant authenticatedAt() {
        return authenticatedAt;
    }

    public UUID sessionBinding() {
        return sessionBinding;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return this;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (!authenticated) {
            throw new IllegalArgumentException("IdP session authentication is immutable.");
        }
    }

    @Override
    public String getName() {
        return Long.toString(accountId);
    }

    @Override
    public String toString() {
        return "IdpSessionAuthentication[accountId=" + accountId + ", companyId=" + companyId
                + ", userId=" + userId + ", roles=" + roles + ", sub=" + sub
                + ", authenticatedAt=" + authenticatedAt + "]";
    }
}
