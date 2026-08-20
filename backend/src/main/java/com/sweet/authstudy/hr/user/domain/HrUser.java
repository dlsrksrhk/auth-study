package com.sweet.authstudy.hr.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public final class HrUser {

    private final Long id;
    private final long companyId;
    private final String code;
    private final String employeeNumber;
    private final Instant createdAt;
    private String name;
    private String phone;
    private LocalDate hiredAt;
    private String workplace;
    private String profileImageUrl;
    private long positionId;
    private UserStatus status;
    private long version;
    private Instant updatedAt;

    private HrUser(
            Long id,
            long companyId,
            String code,
            String employeeNumber,
            String name,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.code = Objects.requireNonNull(code);
        this.employeeNumber = Objects.requireNonNull(employeeNumber);
        this.name = Objects.requireNonNull(name);
        this.phone = Objects.requireNonNull(phone);
        this.hiredAt = Objects.requireNonNull(hiredAt);
        this.workplace = Objects.requireNonNull(workplace);
        this.profileImageUrl = profileImageUrl;
        this.positionId = positionId;
        this.status = Objects.requireNonNull(status);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static HrUser create(
            long companyId,
            String code,
            String employeeNumber,
            String name,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            Instant now) {
        return new HrUser(
                null, companyId, code, employeeNumber, name, phone, hiredAt, workplace,
                profileImageUrl, positionId, UserStatus.PENDING, 0, now, now);
    }

    public static HrUser restore(
            Long id,
            long companyId,
            String code,
            String employeeNumber,
            String name,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new HrUser(
                id, companyId, code, employeeNumber, name, phone, hiredAt, workplace,
                profileImageUrl, positionId, status, version, createdAt, updatedAt);
    }

    public void update(
            String name,
            String phone,
            LocalDate hiredAt,
            String workplace,
            String profileImageUrl,
            long positionId,
            Instant now) {
        this.name = Objects.requireNonNull(name);
        this.phone = Objects.requireNonNull(phone);
        this.hiredAt = Objects.requireNonNull(hiredAt);
        this.workplace = Objects.requireNonNull(workplace);
        this.profileImageUrl = profileImageUrl;
        this.positionId = positionId;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void changeStatus(UserStatus status, Instant now) {
        this.status = Objects.requireNonNull(status);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void updateProfile(
            String name, String phone, LocalDate hiredAt, String workplace,
            String profileImageUrl, long positionId, Instant now) {
        this.name = Objects.requireNonNull(name);
        this.phone = Objects.requireNonNull(phone);
        this.hiredAt = Objects.requireNonNull(hiredAt);
        this.workplace = Objects.requireNonNull(workplace);
        this.profileImageUrl = profileImageUrl;
        this.positionId = positionId;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long id() { return id; }
    public long companyId() { return companyId; }
    public String code() { return code; }
    public String employeeNumber() { return employeeNumber; }
    public String name() { return name; }
    public String phone() { return phone; }
    public LocalDate hiredAt() { return hiredAt; }
    public String workplace() { return workplace; }
    public String profileImageUrl() { return profileImageUrl; }
    public long positionId() { return positionId; }
    public UserStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
