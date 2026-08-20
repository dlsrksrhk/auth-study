package com.sweet.authstudy.hr.department.presentation;

import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.sweet.authstudy.shared.validation.ValidCode;

public final class DepartmentRequests {
    private DepartmentRequests() {}

    public record CreateDepartmentRequest(
            @ValidCode String code,
            @NotBlank @Size(max = 100) String name,
            @ValidCode(nullable = true) String parentCode) {}

    public record UpdateDepartmentRequest(
            @NotBlank @Size(max = 100) String name,
            @ValidCode(nullable = true) String parentCode,
            @NotNull DepartmentStatus status,
            @NotNull Long version) {}
}
