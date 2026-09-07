package com.sweet.authstudy.hr.membership.application;

import com.sweet.authstudy.hr.membership.domain.DepartmentRole;

import java.time.Instant;

public final class MembershipCommands {

    private MembershipCommands() {
    }

    public record AssignMembershipCommand(
            String companyCode,
            String userCode,
            String departmentCode,
            DepartmentRole role,
            boolean primary,
            Instant startedAt) {
    }

    public record UpdateMembershipCommand(
            String companyCode,
            String userCode,
            long membershipId,
            DepartmentRole role,
            boolean primary,
            long version) {
    }
}
