package com.sweet.authstudy.hr.membership.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipJpaRepository extends JpaRepository<MembershipJpaEntity, Long> {

    Optional<MembershipJpaEntity> findByUserIdAndPrimaryTrueAndEndedAtIsNull(long userId);

    List<MembershipJpaEntity> findAllByUserIdOrderByStartedAtDescIdDesc(long userId);

    boolean existsByUserIdAndDepartmentIdAndEndedAtIsNull(long userId, long departmentId);

    boolean existsByUserIdAndPrimaryTrueAndEndedAtIsNull(long userId);

    boolean existsByUserIdAndPrimaryTrueAndEndedAtIsNullAndIdNot(long userId, long id);

    boolean existsByDepartmentIdAndRoleAndEndedAtIsNull(
            long departmentId, com.sweet.authstudy.hr.membership.domain.DepartmentRole role);

    boolean existsByDepartmentIdAndRoleAndEndedAtIsNullAndIdNot(
            long departmentId, com.sweet.authstudy.hr.membership.domain.DepartmentRole role, long id);

    boolean existsByDepartmentIdAndEndedAtIsNull(long departmentId);
}
