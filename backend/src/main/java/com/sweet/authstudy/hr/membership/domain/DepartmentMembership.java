package com.sweet.authstudy.hr.membership.domain;

import java.time.Instant;
import java.util.Objects;

public final class DepartmentMembership {

    private final Long id;
    private final long companyId;
    private final long userId;
    private final long departmentId;
    private final Instant startedAt;
    private final Instant createdAt;
    private DepartmentRole role;
    private boolean primary;
    private Instant endedAt;
    private long version;
    private Instant updatedAt;

    private DepartmentMembership(
            Long id,
            long companyId,
            long userId,
            long departmentId,
            DepartmentRole role,
            boolean primary,
            Instant startedAt,
            Instant endedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.userId = userId;
        this.departmentId = departmentId;
        this.role = Objects.requireNonNull(role);
        this.primary = primary;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.endedAt = endedAt;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static DepartmentMembership create(
            long companyId,
            long userId,
            long departmentId,
            DepartmentRole role,
            boolean primary,
            Instant startedAt,
            Instant now) {
        return new DepartmentMembership(
                null, companyId, userId, departmentId, role, primary, startedAt, null, 0, now, now);
    }

    public static DepartmentMembership restore(
            Long id,
            long companyId,
            long userId,
            long departmentId,
            DepartmentRole role,
            boolean primary,
            Instant startedAt,
            Instant endedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new DepartmentMembership(
                id, companyId, userId, departmentId, role, primary, startedAt, endedAt,
                version, createdAt, updatedAt);
    }

    public void update(DepartmentRole role, boolean primary, Instant now) {
        this.role = Objects.requireNonNull(role);
        this.primary = primary;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void end(Instant endedAt, Instant now) {
        this.endedAt = Objects.requireNonNull(endedAt);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long id() { return id; }
    public long companyId() { return companyId; }
    public long userId() { return userId; }
    public long departmentId() { return departmentId; }
    public DepartmentRole role() { return role; }
    public boolean primary() { return primary; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
