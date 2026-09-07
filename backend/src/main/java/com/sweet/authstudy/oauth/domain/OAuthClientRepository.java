package com.sweet.authstudy.oauth.domain;

import com.sweet.authstudy.shared.application.PageResult;

import java.util.List;
import java.util.Optional;

public interface OAuthClientRepository {

    OAuthClient save(OAuthClient client);

    Optional<OAuthClient> findById(long id);

    default Optional<OAuthClient> findByIdForUpdate(long id) {
        return findById(id);
    }

    Optional<OAuthClient> findByClientId(String clientId);

    List<OAuthClient> findByCompanyId(long companyId);

    PageResult<OAuthClient> findPageByCompanyId(long companyId, int page, int size);

    PageResult<OAuthClient> findPage(int page, int size);
}
