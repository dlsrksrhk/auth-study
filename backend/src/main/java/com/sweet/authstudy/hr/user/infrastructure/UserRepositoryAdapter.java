package com.sweet.authstudy.hr.user.infrastructure;

import java.util.Optional;

import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository repository;

    public UserRepositoryAdapter(UserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public HrUser save(HrUser user) {
        UserJpaEntity entity = user.id() == null
                ? UserJpaEntity.from(user)
                : repository.findById(user.id())
                        .orElseThrow(() -> new IllegalStateException("User does not exist."));
        if (user.id() != null) {
            entity.updateFrom(user);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<HrUser> findById(long id) {
        return repository.findById(id).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<HrUser> findByCompanyIdAndCode(long companyId, String code) {
        return repository.findByCompanyIdAndCodeIgnoreCase(companyId, code).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<HrUser> findByCompanyIdAndEmployeeNumber(long companyId, String employeeNumber) {
        return repository.findByCompanyIdAndEmployeeNumber(companyId, employeeNumber).map(UserJpaEntity::toDomain);
    }
}
