package com.sweet.authstudy.oauth.domain;

import java.util.Optional;

public interface OAuthSubjectRepository {

    Optional<OAuthSubject> findByAccountId(long accountId);

    void insertIfAbsent(OAuthSubject subject);

    OAuthSubject save(OAuthSubject subject);
}
