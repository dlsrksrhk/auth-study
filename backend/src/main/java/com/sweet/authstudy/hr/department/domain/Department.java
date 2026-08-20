package com.sweet.authstudy.hr.department.domain;

import java.time.Instant;
import java.util.Objects;

public final class Department {

    private final Long id;
    private final long companyId;
    private final String code;
    private final Instant createdAt;
    private Long parentDepartmentId;
    private String name;
    private DepartmentStatus status;
    private long version;
    private Instant updatedAt;

    private Department(
            Long id,
            long companyId,
            Long parentDepartmentId,
            String code,
            String name,
            DepartmentStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.parentDepartmentId = parentDepartmentId;
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Department create(
            long companyId, Long parentDepartmentId, String code, String name, Instant now) {
        return new Department(
                null, companyId, parentDepartmentId, code, name, DepartmentStatus.ACTIVE, 0, now, now);
    }

    public static Department restore(
            Long id,
            long companyId,
            Long parentDepartmentId,
            String code,
            String name,
            DepartmentStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Department(
                id, companyId, parentDepartmentId, code, name, status, version, createdAt, updatedAt);
    }

    public void move(Long parentDepartmentId, Instant now) {
        this.parentDepartmentId = parentDepartmentId;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void changeStatus(DepartmentStatus status, Instant now) {
        this.status = Objects.requireNonNull(status);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long id() { return id; }
    public long companyId() { return companyId; }
    public Long parentDepartmentId() { return parentDepartmentId; }
    public String code() { return code; }
    public String name() { return name; }
    public DepartmentStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
