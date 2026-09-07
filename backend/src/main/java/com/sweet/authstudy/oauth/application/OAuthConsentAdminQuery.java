package com.sweet.authstudy.oauth.application;

import com.sweet.authstudy.shared.application.PageResult;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OAuthConsentAdminQuery {
    PageResult<Entry> findPage(long companyId, long registeredClientId, int page, int size);

    Optional<Entry> find(long companyId, long registeredClientId, UUID subject);

    record Entry(long accountId, UUID subject, Set<String> approvedScopes, Instant grantedAt, Instant updatedAt) {
    }
}
