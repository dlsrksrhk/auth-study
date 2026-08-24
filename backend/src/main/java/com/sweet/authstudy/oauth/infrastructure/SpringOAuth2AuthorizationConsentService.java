package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthConsentService;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@Primary
public final class SpringOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final OAuthConsentService consents;

    public SpringOAuth2AuthorizationConsentService(OAuthConsentService consents) {
        this.consents = consents;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
        long clientId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getRegisteredClientId(), "registered client id");
        long accountId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getPrincipalName(), "principal account id");
        consents.approve(accountId, clientId, authorizationConsent.getScopes());
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
        long clientId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getRegisteredClientId(), "registered client id");
        long accountId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getPrincipalName(), "principal account id");
        consents.remove(accountId, clientId);
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        Long clientId = tryParse(registeredClientId);
        Long accountId = tryParse(principalName);
        if (clientId == null || accountId == null) return null;
        return consents.find(accountId, clientId)
                .map(consent -> {
                    OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(
                            registeredClientId, principalName);
                    consent.scopes().forEach(builder::scope);
                    return builder.build();
                }).orElse(null);
    }

    private Long tryParse(String value) {
        try {
            return OAuthAuthorizationMapper.parseId(value, "identifier");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
