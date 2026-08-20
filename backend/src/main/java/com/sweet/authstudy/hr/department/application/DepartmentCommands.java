package com.sweet.authstudy.hr.department.application;

import com.sweet.authstudy.hr.department.domain.DepartmentStatus;

public final class DepartmentCommands {

    private DepartmentCommands() {
    }

    public record CreateDepartmentCommand(
            String companyCode, String code, String name, String parentCode) {
    }

    public record MoveDepartmentCommand(
            String companyCode, String code, String newParentCode, long version) {
    }

    public record ChangeDepartmentStatusCommand(
            String companyCode, String code, DepartmentStatus status, long version) {
    }
}
