package com.sweet.authstudy.hr.membership.infrastructure;

import com.sweet.authstudy.hr.membership.domain.DepartmentMembership;
import com.sweet.authstudy.hr.membership.domain.DepartmentRole;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "department_memberships")
class MembershipJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private long companyId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "department_id", nullable = false)
    private long departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepartmentRole role;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipJpaEntity() {
    }

    private MembershipJpaEntity(DepartmentMembership membership) {
        this.companyId = membership.companyId();
        this.userId = membership.userId();
        this.departmentId = membership.departmentId();
        this.startedAt = membership.startedAt();
        this.createdAt = membership.createdAt();
        updateFrom(membership);
    }

    static MembershipJpaEntity from(DepartmentMembership membership) {
        return new MembershipJpaEntity(membership);
    }

    void updateFrom(DepartmentMembership membership) {
        this.role = membership.role();
        this.primary = membership.primary();
        this.endedAt = membership.endedAt();
        this.updatedAt = membership.updatedAt();
    }

    DepartmentMembership toDomain() {
        return DepartmentMembership.restore(
                id, companyId, userId, departmentId, role, primary, startedAt, endedAt,
                version, createdAt, updatedAt);
    }
}
