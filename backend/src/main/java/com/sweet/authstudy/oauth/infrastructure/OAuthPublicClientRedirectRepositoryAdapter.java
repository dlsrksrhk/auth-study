package com.sweet.authstudy.oauth.infrastructure;

import java.util.List;

import com.sweet.authstudy.oauth.domain.OAuthPublicClientRedirectRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OAuthPublicClientRedirectRepositoryAdapter
        implements OAuthPublicClientRedirectRepository {

    private final OAuthClientJpaRepository clients;

    public OAuthPublicClientRedirectRepositoryAdapter(OAuthClientJpaRepository clients) {
        this.clients = clients;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findActivePublicAuthorizationRedirectUris() {
        return clients.findActivePublicAuthorizationRedirectUris();
    }
}
