package com.sweet.authstudy.oauth.domain;

import java.util.List;

/**
 * Read-only scalar projection used by protocol CORS; it never materializes client secrets.
 */
public interface OAuthPublicClientRedirectRepository {
    List<String> findActivePublicAuthorizationRedirectUris();
}
