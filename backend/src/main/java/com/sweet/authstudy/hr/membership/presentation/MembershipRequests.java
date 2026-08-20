package com.sweet.authstudy.hr.membership.presentation;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class MembershipRequests {
    private MembershipRequests() {}

    public record AssignMembershipRequest(
            @NotBlank @Size(max = 50) String departmentCode,
            @NotNull DepartmentRole role,
            @NotNull Boolean primary,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt) {}

    public record UpdateMembershipRequest(
            @NotNull DepartmentRole role,
            @NotNull Boolean primary,
            @NotNull Long version) {}
}
