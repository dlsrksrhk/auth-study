package com.sweet.authstudy.hr.department.infrastructure;

import com.sweet.authstudy.hr.department.domain.Department;
import com.sweet.authstudy.hr.department.domain.DepartmentStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "departments")
class DepartmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private long companyId;

    @Column(name = "parent_department_id")
    private Long parentDepartmentId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepartmentStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DepartmentJpaEntity() {
    }

    private DepartmentJpaEntity(Department department) {
        this.companyId = department.companyId();
        this.code = department.code();
        this.createdAt = department.createdAt();
        updateFrom(department);
    }

    static DepartmentJpaEntity from(Department department) {
        return new DepartmentJpaEntity(department);
    }

    void updateFrom(Department department) {
        this.parentDepartmentId = department.parentDepartmentId();
        this.name = department.name();
        this.status = department.status();
        this.updatedAt = department.updatedAt();
    }

    Department toDomain() {
        return Department.restore(
                id, companyId, parentDepartmentId, code, name, status, version, createdAt, updatedAt);
    }
}
