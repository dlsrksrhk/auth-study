package com.sweet.authstudy.hr.membership.domain;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository {

    DepartmentMembership save(DepartmentMembership membership);

    Optional<DepartmentMembership> findById(long id);

    Optional<DepartmentMembership> findActivePrimaryByUserId(long userId);

    List<DepartmentMembership> findAllByUserId(long userId);

    boolean existsActiveByUserIdAndDepartmentId(long userId, long departmentId);

    boolean existsActivePrimaryByUserId(long userId);

    boolean existsActivePrimaryByUserIdExcluding(long userId, long membershipId);

    boolean existsActiveHeadByDepartmentId(long departmentId);

    boolean existsActiveHeadByDepartmentIdExcluding(long departmentId, long membershipId);

    boolean existsActiveByDepartmentId(long departmentId);
}
