package com.sweet.authstudy.identity.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class Account {

    private final Long id;
    private final Long companyId;
    private final Long userId;
    private final String loginEmail;
    private final Instant createdAt;
    private String passwordHash;
    private AccountStatus status;
    private boolean mustChangePassword;
    private int failedLoginAttempts;
    private Instant lockedUntil;
    private final EnumSet<AccountRole> roles;
    private long version;
    private Instant updatedAt;

    private Account(
            Long id,
            Long companyId,
            Long userId,
            String loginEmail,
            String passwordHash,
            AccountStatus status,
            boolean mustChangePassword,
            int failedLoginAttempts,
            Instant lockedUntil,
            Set<AccountRole> roles,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.userId = userId;
        this.loginEmail = Objects.requireNonNull(loginEmail);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.status = Objects.requireNonNull(status);
        this.mustChangePassword = mustChangePassword;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedUntil = lockedUntil;
        this.roles = roles.isEmpty() ? EnumSet.noneOf(AccountRole.class) : EnumSet.copyOf(roles);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        validateOwnership();
    }

    public static Account createCompanyAccount(
            long companyId,
            long userId,
            String loginEmail,
            String passwordHash,
            Instant now) {
        return new Account(
                null, companyId, userId, loginEmail, passwordHash, AccountStatus.ACTIVE, true,
                0, null, Set.of(AccountRole.USER), 0, now, now);
    }

    public static Account createSystemAdmin(
            String loginEmail,
            String passwordHash,
            boolean mustChangePassword,
            Instant now) {
        return new Account(
                null, null, null, loginEmail, passwordHash, AccountStatus.ACTIVE, mustChangePassword,
                0, null, Set.of(AccountRole.SYSTEM_ADMIN), 0, now, now);
    }

    public static Account restore(
            Long id,
            Long companyId,
            Long userId,
            String loginEmail,
            String passwordHash,
            AccountStatus status,
            boolean mustChangePassword,
            int failedLoginAttempts,
            Instant lockedUntil,
            Set<AccountRole> roles,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Account(
                id, companyId, userId, loginEmail, passwordHash, status, mustChangePassword,
                failedLoginAttempts, lockedUntil, roles, version, createdAt, updatedAt);
    }

    public void resetTemporaryPassword(String passwordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.mustChangePassword = true;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.mustChangePassword = false;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void recordFailedLogin(Instant lockedUntil, Instant now) {
        this.failedLoginAttempts++;
        this.lockedUntil = lockedUntil;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void clearFailedLogins(Instant now) {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void changeStatus(AccountStatus status, Instant now) {
        this.status = Objects.requireNonNull(status);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void addRole(AccountRole role, Instant now) {
        this.roles.add(Objects.requireNonNull(role));
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void removeRole(AccountRole role, Instant now) {
        this.roles.remove(Objects.requireNonNull(role));
        this.updatedAt = Objects.requireNonNull(now);
    }

    private void validateOwnership() {
        if ((companyId == null) != (userId == null)) {
            throw new IllegalArgumentException("Company and user must both be present or absent.");
        }
        if (companyId == null && !roles.equals(EnumSet.of(AccountRole.SYSTEM_ADMIN))) {
            throw new IllegalArgumentException("A system account must only have the system administrator role.");
        }
        if (companyId != null && roles.contains(AccountRole.SYSTEM_ADMIN)) {
            throw new IllegalArgumentException("A company account cannot have the system administrator role.");
        }
    }

    public Long id() { return id; }
    public Long companyId() { return companyId; }
    public Long userId() { return userId; }
    public String loginEmail() { return loginEmail; }
    public String passwordHash() { return passwordHash; }
    public AccountStatus status() { return status; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public int failedLoginAttempts() { return failedLoginAttempts; }
    public Instant lockedUntil() { return lockedUntil; }
    public Set<AccountRole> roles() { return Collections.unmodifiableSet(roles); }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
