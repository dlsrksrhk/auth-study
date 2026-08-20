package com.sweet.authstudy.hr.user.infrastructure;

import java.time.Instant;
import java.time.LocalDate;

import com.sweet.authstudy.hr.user.domain.HrUser;
import com.sweet.authstudy.hr.user.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "users")
class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private long companyId;

    @Column(nullable = false)
    private String code;

    @Column(name = "employee_number", nullable = false)
    private String employeeNumber;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    @Column(name = "hired_at", nullable = false)
    private LocalDate hiredAt;

    @Column(nullable = false)
    private String workplace;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "position_id", nullable = false)
    private long positionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserJpaEntity() {
    }

    private UserJpaEntity(HrUser user) {
        this.companyId = user.companyId();
        this.code = user.code();
        this.employeeNumber = user.employeeNumber();
        updateFrom(user);
        this.createdAt = user.createdAt();
    }

    static UserJpaEntity from(HrUser user) {
        return new UserJpaEntity(user);
    }

    void updateFrom(HrUser user) {
        this.name = user.name();
        this.phone = user.phone();
        this.hiredAt = user.hiredAt();
        this.workplace = user.workplace();
        this.profileImageUrl = user.profileImageUrl();
        this.positionId = user.positionId();
        this.status = user.status();
        this.updatedAt = user.updatedAt();
    }

    HrUser toDomain() {
        return HrUser.restore(
                id, companyId, code, employeeNumber, name, phone, hiredAt, workplace, profileImageUrl,
                positionId, status, version, createdAt, updatedAt);
    }
}
