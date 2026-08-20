package com.sweet.authstudy.identity.domain;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(long id);

    Optional<Account> findByUserId(long userId);

    Optional<Account> findCompanyAccount(long companyId, String loginEmail);

    Optional<Account> findSystemByEmail(String loginEmail);
}
