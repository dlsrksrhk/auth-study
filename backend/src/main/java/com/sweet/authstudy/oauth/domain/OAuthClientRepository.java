package com.sweet.authstudy.oauth.domain;

import java.util.List;
import java.util.Optional;

public interface OAuthClientRepository {

    OAuthClient save(OAuthClient client);

    Optional<OAuthClient> findById(long id);

    Optional<OAuthClient> findByClientId(String clientId);

    List<OAuthClient> findByCompanyId(long companyId);
}
