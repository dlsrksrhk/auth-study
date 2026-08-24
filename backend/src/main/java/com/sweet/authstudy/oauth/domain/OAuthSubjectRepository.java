package com.sweet.authstudy.oauth.domain;

import java.util.Optional;

public interface OAuthSubjectRepository {

    Optional<OAuthSubject> findByAccountId(long accountId);

    OAuthSubject save(OAuthSubject subject);
}
