package com.sweet.authstudy.hr.company.infrastructure;

import com.sweet.authstudy.hr.company.domain.Company;
import com.sweet.authstudy.hr.company.domain.CompanyStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "companies")
class CompanyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "email_domain", nullable = false)
    private String emailDomain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompanyJpaEntity() {
    }

    private CompanyJpaEntity(Company company) {
        this.code = company.code();
        this.name = company.name();
        this.emailDomain = company.emailDomain();
        this.status = company.status();
        this.createdAt = company.createdAt();
        this.updatedAt = company.updatedAt();
    }

    static CompanyJpaEntity from(Company company) {
        return new CompanyJpaEntity(company);
    }

    void updateFrom(Company company) {
        this.name = company.name();
        this.status = company.status();
        this.updatedAt = company.updatedAt();
    }

    Company toDomain() {
        return Company.restore(id, code, name, emailDomain, status, version, createdAt, updatedAt);
    }
}
