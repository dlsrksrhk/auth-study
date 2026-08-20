package com.sweet.authstudy.identity.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepositoryAdapter implements AccountRepository {

    private final AccountJpaRepository repository;

    public AccountRepositoryAdapter(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Account save(Account account) {
        AccountJpaEntity entity = account.id() == null
                ? AccountJpaEntity.from(account)
                : repository.findById(account.id())
                        .orElseThrow(() -> new IllegalStateException("Account does not exist."));
        if (account.id() != null) {
            entity.updateFrom(account);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Account> findById(long id) {
        return repository.findById(id).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByUserId(long userId) {
        return repository.findByUserId(userId).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findCompanyAccount(long companyId, String loginEmail) {
        return repository.findByCompanyIdAndLoginEmailIgnoreCase(companyId, loginEmail)
                .map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findSystemByEmail(String loginEmail) {
        return repository.findByCompanyIdIsNullAndLoginEmailIgnoreCase(loginEmail)
                .map(AccountJpaEntity::toDomain);
    }
}
