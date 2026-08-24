package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.oauth.domain.OAuthConsent;
import com.sweet.authstudy.oauth.domain.OAuthConsentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthConsentRepositoryAdapter implements OAuthConsentRepository {

    private final OAuthConsentJpaRepository repository;

    public OAuthConsentRepositoryAdapter(OAuthConsentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public OAuthConsent save(OAuthConsent consent) {
        OAuthConsentJpaEntity entity = consent.id() == null
                ? OAuthConsentJpaEntity.from(consent)
                : repository.findById(consent.id())
                        .orElseThrow(() -> new IllegalStateException("OAuth consent does not exist."));
        if (consent.id() != null) entity.updateFrom(consent);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OAuthConsent> findByAccountIdAndRegisteredClientId(long accountId, long registeredClientId) {
        return repository.findByPrincipalAccountIdAndRegisteredClientId(accountId, registeredClientId)
                .map(OAuthConsentJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<OAuthConsent> findByAccountIdAndRegisteredClientIdForUpdate(
            long accountId, long registeredClientId) {
        return repository.findForUpdate(accountId, registeredClientId).map(OAuthConsentJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void lockDecision(long accountId, long registeredClientId) {
        repository.lockDecision(accountId, registeredClientId);
    }

    @Override
    @Transactional
    public void remove(long accountId, long registeredClientId) {
        repository.deleteByPrincipalAccountIdAndRegisteredClientId(accountId, registeredClientId);
    }
}
