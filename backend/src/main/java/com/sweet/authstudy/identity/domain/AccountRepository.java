package com.sweet.authstudy.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Collection;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(long id);

    Optional<Account> findByIdForUpdate(long id);

    Optional<Account> findByUserId(long userId);

    List<Account> findAllByCompanyIdForUpdate(long companyId);

    List<Account> findAllByUserIds(Collection<Long> userIds);

    Optional<Account> findCompanyAccount(long companyId, String loginEmail);

    Optional<Account> findSystemByEmail(String loginEmail);

    Optional<LoginSnapshot> findCompanyLoginSnapshot(long companyId, String loginEmail);

    Optional<LoginSnapshot> findSystemLoginSnapshot(String loginEmail);

    record LoginSnapshot(long accountId, String passwordHash, AccountStatus status, Instant lockedUntil) {}
}
