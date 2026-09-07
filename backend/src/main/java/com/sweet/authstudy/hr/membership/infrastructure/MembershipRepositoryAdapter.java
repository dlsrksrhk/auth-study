package com.sweet.authstudy.hr.membership.infrastructure;

import com.sweet.authstudy.hr.membership.domain.DepartmentMembership;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import com.sweet.authstudy.hr.membership.domain.MembershipRepository;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MembershipRepositoryAdapter implements MembershipRepository {

    private final MembershipJpaRepository repository;

    public MembershipRepositoryAdapter(MembershipJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public DepartmentMembership save(DepartmentMembership membership) {
        MembershipJpaEntity entity = membership.id() == null
                ? MembershipJpaEntity.from(membership)
                : repository.findById(membership.id())
                .orElseThrow(() -> new IllegalStateException("Membership does not exist."));
        if (membership.id() != null) {
            entity.updateFrom(membership);
        }
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<DepartmentMembership> findById(long id) {
        return repository.findById(id).map(MembershipJpaEntity::toDomain);
    }

    @Override
    public Optional<DepartmentMembership> findActivePrimaryByUserId(long userId) {
        return repository.findByUserIdAndPrimaryTrueAndEndedAtIsNull(userId)
                .map(MembershipJpaEntity::toDomain);
    }

    @Override
    public List<DepartmentMembership> findAllByUserId(long userId) {
        return repository.findAllByUserIdOrderByStartedAtDescIdDesc(userId).stream()
                .map(MembershipJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsActiveByUserIdAndDepartmentId(long userId, long departmentId) {
        return repository.existsByUserIdAndDepartmentIdAndEndedAtIsNull(userId, departmentId);
    }

    @Override
    public boolean existsActivePrimaryByUserId(long userId) {
        return repository.existsByUserIdAndPrimaryTrueAndEndedAtIsNull(userId);
    }

    @Override
    public boolean existsActivePrimaryByUserIdExcluding(long userId, long membershipId) {
        return repository.existsByUserIdAndPrimaryTrueAndEndedAtIsNullAndIdNot(userId, membershipId);
    }

    @Override
    public boolean existsActiveHeadByDepartmentId(long departmentId) {
        return repository.existsByDepartmentIdAndRoleAndEndedAtIsNull(departmentId, DepartmentRole.HEAD);
    }

    @Override
    public boolean existsActiveHeadByDepartmentIdExcluding(long departmentId, long membershipId) {
        return repository.existsByDepartmentIdAndRoleAndEndedAtIsNullAndIdNot(
                departmentId, DepartmentRole.HEAD, membershipId);
    }

    @Override
    public boolean existsActiveByDepartmentId(long departmentId) {
        return repository.existsByDepartmentIdAndEndedAtIsNull(departmentId);
    }

    @Override
    public PageResult<DepartmentMembership> searchByUser(
            long companyId, long userId, String search, Boolean active,
            int page, int size, String sort) {
        var result = repository.searchByUser(companyId, userId, search, active,
                PageRequest.of(page, size, stableSort(sort, "id")));
        return new PageResult<>(result.getContent().stream().map(MembershipJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private Sort stableSort(String requested, String tieBreaker) {
        Sort sort = Sort.by(requested);
        return requested.equals(tieBreaker) ? sort : sort.and(Sort.by(tieBreaker));
    }
}
