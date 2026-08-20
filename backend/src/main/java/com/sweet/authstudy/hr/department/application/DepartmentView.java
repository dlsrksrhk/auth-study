package com.sweet.authstudy.hr.department.application;

import java.time.Instant;

import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;

public record DepartmentView(
        Long id,
        long companyId,
        Long parentDepartmentId,
        String code,
        String name,
        DepartmentStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static DepartmentView from(Department department) {
        return new DepartmentView(
                department.id(),
                department.companyId(),
                department.parentDepartmentId(),
                department.code(),
                department.name(),
                department.status(),
                department.version(),
                department.createdAt(),
                department.updatedAt());
    }
}
