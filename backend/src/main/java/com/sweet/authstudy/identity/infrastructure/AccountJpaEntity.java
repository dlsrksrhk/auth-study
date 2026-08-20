package com.sweet.authstudy.identity.infrastructure;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.sweet.authstudy.identity.domain.Account;
import com.sweet.authstudy.identity.domain.AccountRole;
import com.sweet.authstudy.identity.domain.AccountStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
class AccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "login_email", nullable = false)
    private String loginEmail;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "account_roles", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<AccountRole> roles = EnumSet.noneOf(AccountRole.class);

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountJpaEntity() {
    }

    private AccountJpaEntity(Account account) {
        this.companyId = account.companyId();
        this.userId = account.userId();
        this.loginEmail = account.loginEmail();
        updateFrom(account);
        this.createdAt = account.createdAt();
    }

    static AccountJpaEntity from(Account account) {
        return new AccountJpaEntity(account);
    }

    void updateFrom(Account account) {
        this.passwordHash = account.passwordHash();
        this.status = account.status();
        this.mustChangePassword = account.mustChangePassword();
        this.failedLoginAttempts = account.failedLoginAttempts();
        this.lockedUntil = account.lockedUntil();
        this.roles.clear();
        this.roles.addAll(account.roles());
        this.updatedAt = account.updatedAt();
    }

    Account toDomain() {
        return Account.restore(
                id, companyId, userId, loginEmail, passwordHash, status, mustChangePassword,
                failedLoginAttempts, lockedUntil, roles, version, createdAt, updatedAt);
    }
}
