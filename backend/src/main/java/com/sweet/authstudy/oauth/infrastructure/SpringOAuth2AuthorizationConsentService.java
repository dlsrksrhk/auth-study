package com.sweet.authstudy.oauth.infrastructure;

import java.time.Clock;

import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@Primary
public final class SpringOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final OAuthConsentRepository consents;
    private final OAuthClientRepository clients;
    private final Clock clock;

    public SpringOAuth2AuthorizationConsentService(OAuthConsentRepository consents,
            OAuthClientRepository clients, Clock clock) {
        this.consents = consents;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
        long clientId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getRegisteredClientId(), "registered client id");
        long accountId = OAuthAuthorizationMapper.parseId(
                authorizationConsent.getPrincipalName(), "principal account id");
        requireActiveClient(clientId);
        OAuthConsent consent = consents.findByAccountIdAndRegisteredClientId(accountId, clientId)
                .orElseGet(() -> OAuthConsent.create(accountId, clientId,
                        authorizationConsent.getScopes(), clock.instant()));
        if (consent.id() != null) consent.replaceScopes(authorizationConsent.getScopes(), clock.instant());
        consents.save(consent);
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
        if (clientId == null || accountId == null || !activeClient(clientId)) return null;
        return consents.findByAccountIdAndRegisteredClientId(accountId, clientId)
                .map(consent -> {
                    OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(
                            registeredClientId, principalName);
                    consent.scopes().forEach(builder::scope);
                    return builder.build();
                }).orElse(null);
    }

    private void requireActiveClient(long clientId) {
        if (!activeClient(clientId)) {
            throw new IllegalArgumentException("Active OAuth client does not exist.");
        }
    }

    private boolean activeClient(long clientId) {
        return clients.findById(clientId)
                .filter(client -> client.status() == OAuthClientStatus.ACTIVE)
                .isPresent();
    }

    private Long tryParse(String value) {
        try {
            return OAuthAuthorizationMapper.parseId(value, "identifier");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
