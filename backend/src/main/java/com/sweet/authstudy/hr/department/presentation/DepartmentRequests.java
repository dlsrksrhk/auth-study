package com.sweet.authstudy.hr.department.presentation;

import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DepartmentRequests {
    private DepartmentRequests() {}

    public record CreateDepartmentRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String parentCode) {}

    public record UpdateDepartmentRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String parentCode,
            @NotNull DepartmentStatus status,
            @NotNull Long version) {}
}
