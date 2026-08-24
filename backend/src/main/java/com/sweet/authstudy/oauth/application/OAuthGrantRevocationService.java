package com.sweet.authstudy.oauth.application;

import java.time.Instant;

import com.sweet.authstudy.identity.application.OAuthGrantRevocationPort;
import com.sweet.authstudy.oauth.domain.OAuthAuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthGrantRevocationService implements OAuthGrantRevocationPort {

    private final OAuthAuthorizationRepository authorizations;

    public OAuthGrantRevocationService(OAuthAuthorizationRepository authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccountScope(long accountId) {
        authorizations.lockByAccountId(accountId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAccount(long accountId, Instant revokedAt) {
        authorizations.revokeByAccountId(accountId, revokedAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeCompany(long companyId, Instant revokedAt) {
        authorizations.revokeByCompanyId(companyId, revokedAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeClient(long registeredClientId, Instant revokedAt) {
        authorizations.revokeByClientId(registeredClientId, revokedAt);
    }
}
