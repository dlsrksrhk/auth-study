package com.sweet.authstudy.oauth.domain;

public interface OAuthProtocolEventRepository {
    /** Joins the current business transaction and is allowed to fail it. */
    OAuthProtocolEvent saveRequired(OAuthProtocolEvent event);

    /** Uses an isolated transaction for rejection/failure history. */
    OAuthProtocolEvent saveBestEffort(OAuthProtocolEvent event);
}
