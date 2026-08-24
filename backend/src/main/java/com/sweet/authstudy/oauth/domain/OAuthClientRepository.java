package com.sweet.authstudy.oauth.domain;

import java.util.List;
import java.util.Optional;

public interface OAuthClientRepository {

    OAuthClient save(OAuthClient client);

    Optional<OAuthClient> findById(long id);
    default Optional<OAuthClient> findByIdForUpdate(long id) { return findById(id); }

    Optional<OAuthClient> findByClientId(String clientId);

    List<OAuthClient> findByCompanyId(long companyId);

    default List<OAuthClient> findAll() { return List.of(); }
}
