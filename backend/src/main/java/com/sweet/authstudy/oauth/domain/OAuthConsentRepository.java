package com.sweet.authstudy.oauth.domain;

import java.util.Optional;

public interface OAuthConsentRepository {
    OAuthConsent save(OAuthConsent consent);

    Optional<OAuthConsent> findByAccountIdAndRegisteredClientId(long accountId, long registeredClientId);

    default Optional<OAuthConsent> findByAccountIdAndRegisteredClientIdForUpdate(
            long accountId, long registeredClientId) {
        return findByAccountIdAndRegisteredClientId(accountId, registeredClientId);
    }

    default void lockDecision(long accountId, long registeredClientId) {
    }

    void remove(long accountId, long registeredClientId);
}
