package com.sweet.authstudy.hr.membership.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    @Query("""
            select m from MembershipJpaEntity m
            join UserJpaEntity u on u.id = m.userId
            join DepartmentJpaEntity d on d.id = m.departmentId
            where m.companyId = :companyId and m.userId = :userId
              and (:active is null
                or (:active = true and m.endedAt is null)
                or (:active = false and m.endedAt is not null))
              and (:search = ''
                or lower(u.code) like lower(concat('%', :search, '%'))
                or lower(u.name) like lower(concat('%', :search, '%'))
                or lower(u.employeeNumber) like lower(concat('%', :search, '%'))
                or lower(d.code) like lower(concat('%', :search, '%'))
                or lower(d.name) like lower(concat('%', :search, '%')))
            """)
    Page<MembershipJpaEntity> searchByUser(
            @Param("companyId") long companyId,
            @Param("userId") long userId,
            @Param("search") String search,
            @Param("active") Boolean active,
            Pageable pageable);
}
