package com.sweet.authstudy.oauth.infrastructure;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Component;

/**
 * Restricts synthetic ID metadata to the authorization lookup performed by the UserInfo provider.
 */
@Component
public final class OidcUserInfoAuthorizationService implements OAuth2AuthorizationService {

    private final SpringOAuth2AuthorizationService delegate;

    public OidcUserInfoAuthorizationService(SpringOAuth2AuthorizationService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) return null;
        return delegate.findByAccessTokenForUserInfo(token);
    }
}
