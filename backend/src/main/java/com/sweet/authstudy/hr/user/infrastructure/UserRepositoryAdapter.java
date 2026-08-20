package com.sweet.authstudy.hr.user.infrastructure;

import java.util.Optional;
import java.util.List;

import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserRepository;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Override
    public List<HrUser> findAllByCompanyId(long companyId) {
        return repository.findAllByCompanyId(companyId).stream().map(UserJpaEntity::toDomain).toList();
    }

    @Override
    public UserPage search(
            long companyId, String search, UserStatus status, int page, int size, String sort) {
        var result = repository.search(companyId, search, status,
                PageRequest.of(page, size, stableSort(sort, "code")));
        return new UserPage(result.getContent().stream().map(UserJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private Sort stableSort(String requested, String tieBreaker) {
        Sort sort = Sort.by(requested);
        return requested.equals(tieBreaker) ? sort : sort.and(Sort.by(tieBreaker));
    }
}
