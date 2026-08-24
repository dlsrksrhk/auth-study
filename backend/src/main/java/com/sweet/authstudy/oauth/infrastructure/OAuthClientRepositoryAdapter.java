package com.sweet.authstudy.oauth.infrastructure;

import java.util.List;
import java.util.Optional;

import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthClientRepositoryAdapter implements OAuthClientRepository {

    private final OAuthClientJpaRepository repository;

    public OAuthClientRepositoryAdapter(OAuthClientJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public OAuthClient save(OAuthClient client) {
        OAuthClientJpaEntity entity = client.id() == null
                ? OAuthClientJpaEntity.from(client)
                : repository.findById(client.id())
                        .orElseThrow(() -> new IllegalStateException("OAuth client does not exist."));
        if (client.id() != null) {
            entity.updateFrom(client);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<OAuthClient> findById(long id) {
        return repository.findById(id).map(OAuthClientJpaEntity::toDomain);
    }

    @Override
    public Optional<OAuthClient> findByIdForUpdate(long id) {
        return repository.findByIdForUpdate(id).map(OAuthClientJpaEntity::toDomain);
    }

    @Override
    public Optional<OAuthClient> findByClientId(String clientId) {
        return repository.findByClientId(clientId).map(OAuthClientJpaEntity::toDomain);
    }

    @Override
    public List<OAuthClient> findByCompanyId(long companyId) {
        return repository.findAllByCompanyId(companyId).stream()
                .map(OAuthClientJpaEntity::toDomain)
                .toList();
    }
}
