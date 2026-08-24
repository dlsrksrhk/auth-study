package com.sweet.authstudy.oauth.domain;

import java.util.Optional;

public interface OAuthConsentRepository {
    OAuthConsent save(OAuthConsent consent);
    Optional<OAuthConsent> findByAccountIdAndRegisteredClientId(long accountId, long registeredClientId);
    void remove(long accountId, long registeredClientId);
}
