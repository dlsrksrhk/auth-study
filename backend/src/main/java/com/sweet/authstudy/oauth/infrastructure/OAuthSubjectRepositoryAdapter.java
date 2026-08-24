package com.sweet.authstudy.oauth.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.oauth.domain.OAuthSubject;
import com.sweet.authstudy.oauth.domain.OAuthSubjectRepository;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthSubjectRepositoryAdapter implements OAuthSubjectRepository {

    private final OAuthSubjectJpaRepository repository;

    public OAuthSubjectRepositoryAdapter(OAuthSubjectJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OAuthSubject> findByAccountId(long accountId) {
        return repository.findByAccountId(accountId).map(OAuthSubjectJpaEntity::toDomain);
    }

    @Override
    public OAuthSubject save(OAuthSubject subject) {
        if (subject.id() != null) {
            return repository.findById(subject.id())
                    .map(OAuthSubjectJpaEntity::toDomain)
                    .orElseThrow(() -> new IllegalStateException("OAuth subject does not exist."));
        }
        return repository.saveAndFlush(OAuthSubjectJpaEntity.from(subject)).toDomain();
    }
}
