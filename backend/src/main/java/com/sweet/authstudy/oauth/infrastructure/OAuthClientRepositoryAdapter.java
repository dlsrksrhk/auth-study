package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    @Override
    public PageResult<OAuthClient> findPageByCompanyId(long companyId, int page, int size) {
        return toPage(repository.findAllByCompanyId(companyId, pageable(page, size)));
    }

    @Override
    public PageResult<OAuthClient> findPage(int page, int size) {
        return toPage(repository.findAllManaged(pageable(page, size)));
    }

    private PageRequest pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by("clientId").ascending().and(Sort.by("id").ascending()));
    }

    private PageResult<OAuthClient> toPage(org.springframework.data.domain.Page<OAuthClientJpaEntity> page) {
        return new PageResult<>(page.getContent().stream().map(OAuthClientJpaEntity::toDomain).toList(),
                page.getTotalElements(), page.getTotalPages());
    }
}
