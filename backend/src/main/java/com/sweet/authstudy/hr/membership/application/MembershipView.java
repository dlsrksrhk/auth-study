package com.sweet.authstudy.hr.membership.application;

import java.time.Instant;

import com.sweet.authstudy.hr.membership.domain.DepartmentMembership;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;

public record MembershipView(
        Long id,
        long companyId,
        long userId,
        long departmentId,
        DepartmentRole role,
        boolean primary,
        Instant startedAt,
        Instant endedAt,
        long version) {

    public static MembershipView from(DepartmentMembership membership) {
        return new MembershipView(
                membership.id(),
                membership.companyId(),
                membership.userId(),
                membership.departmentId(),
                membership.role(),
                membership.primary(),
                membership.startedAt(),
                membership.endedAt(),
                membership.version());
    }
}
