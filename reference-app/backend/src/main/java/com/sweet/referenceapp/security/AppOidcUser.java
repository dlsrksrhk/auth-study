package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppUserView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class AppOidcUser implements OidcUser {
    private final OidcUser delegate;
    private final AppUserView localUser;
    private final List<SimpleGrantedAuthority> authorities;

    public AppOidcUser(OidcUser delegate, AppUserView localUser) {
        this.delegate = Objects.requireNonNull(delegate);
        this.localUser = Objects.requireNonNull(localUser);
        authorities = localUser.roles().stream().map(Enum::name).sorted().map(SimpleGrantedAuthority::new).toList();
    }

    public UUID localUserId() { return localUser.id(); }
    public AppUserView localUser() { return localUser; }
    public AppOidcUser withLocalUser(AppUserView current) { return new AppOidcUser(delegate, current); }
    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String toString() { return "AppOidcUser[localUserId=" + localUserId() + "]"; }
}
