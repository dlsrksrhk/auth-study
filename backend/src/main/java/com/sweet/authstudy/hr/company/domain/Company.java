package com.sweet.authstudy.hr.company.domain;

import java.time.Instant;
import java.util.Objects;

public final class Company {

    private final Long id;
    private final String code;
    private final String emailDomain;
    private final Instant createdAt;
    private String name;
    private CompanyStatus status;
    private long version;
    private Instant updatedAt;

    private Company(
            Long id,
            String code,
            String name,
            String emailDomain,
            CompanyStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.emailDomain = Objects.requireNonNull(emailDomain);
        this.status = Objects.requireNonNull(status);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Company create(String code, String name, String emailDomain, Instant now) {
        return new Company(null, code, name, emailDomain, CompanyStatus.ACTIVE, 0, now, now);
    }

    public static Company restore(
            Long id,
            String code,
            String name,
            String emailDomain,
            CompanyStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Company(id, code, name, emailDomain, status, version, createdAt, updatedAt);
    }

    public void update(String name, CompanyStatus status, Instant now) {
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String emailDomain() {
        return emailDomain;
    }

    public CompanyStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
