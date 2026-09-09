package com.sweet.referenceapp.security;

import com.sweet.referenceapp.user.application.AppLocalLoginService;
import com.sweet.referenceapp.user.application.LocalUserDisabledException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public final class AppOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final OidcExternalIdentityMapper mapper;
    private final AppLocalLoginService login;

    @Autowired
    public AppOidcUserService(OidcExternalIdentityMapper mapper, AppLocalLoginService login) {
        this(standardService(mapper), mapper, login);
    }

    AppOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            OidcExternalIdentityMapper mapper, AppLocalLoginService login) {
        this.delegate = delegate;
        this.mapper = mapper;
        this.login = login;
    }

    private static OidcUserService standardService(OidcExternalIdentityMapper mapper) {
        var standard = new OidcUserService();
        standard.setClaimTypeConverterFactory(registration -> claims -> {
            mapper.validateClaims(claims);
            return claims;
        });
        return standard;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        try {
            var oidc = delegate.loadUser(request);
            var profile = mapper.map(request.getIdToken(), oidc.getUserInfo());
            try {
                return new AppOidcUser(oidc, login.login(profile));
            } catch (LocalUserDisabledException exception) {
                throw new LocalUserLoginAuthenticationException();
            }
        } catch (LocalUserLoginAuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oidc_login_failed"), "OIDC login failed");
        }
    }

    static final class LocalUserLoginAuthenticationException extends OAuth2AuthenticationException {
        private LocalUserLoginAuthenticationException() {
            super(new OAuth2Error("local_user_disabled"), "Local user is disabled");
        }
    }
}
