package com.sweet.authstudy.hr.department.application;

import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;

import java.time.Instant;

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
