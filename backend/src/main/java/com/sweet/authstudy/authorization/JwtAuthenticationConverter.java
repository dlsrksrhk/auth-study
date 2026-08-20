package com.sweet.authstudy.authorization;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.sweet.authstudy.identity.domain.AccountRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roleNames = jwt.getClaimAsStringList("roles");
        Set<AccountRole> roles = roleNames == null ? Set.of() : roleNames.stream()
                .map(AccountRole::valueOf).collect(Collectors.toUnmodifiableSet());
        AuthenticatedAccount principal = new AuthenticatedAccount(
                Long.parseLong(jwt.getSubject()), number(jwt, "company_id"), number(jwt, "user_id"),
                roles, "PASSWORD_CHANGE".equals(jwt.getClaimAsString("purpose")));
        Collection<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        return new PrincipalAuthenticationToken(principal, jwt, authorities);
    }

    private Long number(Jwt jwt, String claim) {
        Number value = jwt.getClaim(claim);
        return value == null ? null : value.longValue();
    }

    private static final class PrincipalAuthenticationToken extends AbstractAuthenticationToken {
        private final AuthenticatedAccount principal;
        private final Jwt credentials;
        private PrincipalAuthenticationToken(AuthenticatedAccount principal, Jwt credentials,
                Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.principal = principal;
            this.credentials = credentials;
            setAuthenticated(true);
        }
        @Override public Object getCredentials() { return credentials; }
        @Override public Object getPrincipal() { return principal; }
    }
}
