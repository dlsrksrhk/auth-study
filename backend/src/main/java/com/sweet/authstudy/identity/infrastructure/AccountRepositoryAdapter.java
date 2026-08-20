package com.sweet.authstudy.identity.infrastructure;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRepository;
import com.sweet.authstudy.identity.domain.AccountRepository.LoginSnapshot;
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
    public Optional<Account> findByIdForUpdate(long id) {
        return repository.findByIdForUpdate(id).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByUserId(long userId) {
        return repository.findByUserId(userId).map(AccountJpaEntity::toDomain);
    }

    @Override
    public List<Account> findAllByUserIds(Collection<Long> userIds) {
        return repository.findAllByUserIdIn(userIds).stream().map(AccountJpaEntity::toDomain).toList();
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

    @Override
    public Optional<LoginSnapshot> findCompanyLoginSnapshot(long companyId, String loginEmail) {
        return repository.findCompanyLoginSnapshot(companyId, loginEmail).map(this::snapshot);
    }

    @Override
    public Optional<LoginSnapshot> findSystemLoginSnapshot(String loginEmail) {
        return repository.findSystemLoginSnapshot(loginEmail).map(this::snapshot);
    }

    private LoginSnapshot snapshot(AccountLoginProjection projection) {
        return new LoginSnapshot(projection.getAccountId(), projection.getPasswordHash(),
                projection.getStatus(), projection.getLockedUntil());
    }

}
