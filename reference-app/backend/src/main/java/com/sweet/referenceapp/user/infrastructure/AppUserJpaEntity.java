package com.sweet.referenceapp.user.infrastructure;

import com.sweet.referenceapp.user.domain.AppRole;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserStatus;
import com.sweet.referenceapp.user.domain.ExternalUserSnapshot;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_user")
public class AppUserJpaEntity {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
    @Column(name = "issuer", updatable = false, nullable = false, length = 1024)
    private String issuer;
    @Column(name = "subject", updatable = false, nullable = false, length = 255)
    private String subject;
    @Column(name = "email")
    private String email;
    @Column(name = "display_name")
    private String displayName;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "company_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> companySnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "organization_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> organizationSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hr_roles_snapshot", nullable = false, columnDefinition = "jsonb")
    private Set<String> hrRolesSnapshot = new HashSet<>();
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AppUserStatus status;
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "app_user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<AppRole> roles = new HashSet<>();

    protected AppUserJpaEntity() {}

    String issuer() { return issuer; }
    String subject() { return subject; }
    long version() { return version; }

    void replaceSnapshot(ExternalUserSnapshot snapshot, Instant updatedAt, Instant lastLoginAt) {
        email = snapshot.email();
        displayName = snapshot.displayName();
        companySnapshot = snapshot.company();
        organizationSnapshot = snapshot.organization();
        hrRolesSnapshot = snapshot.hrRoles();
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
    }

    void addAdministrator(Instant now) {
        if (roles.add(AppRole.APP_ADMIN)) {
            updatedAt = now;
        }
    }

    void updateAdministration(AppUser user) {
        if (status != user.status()) {
            status = user.status();
        }
        roles.retainAll(user.roles());
        roles.addAll(user.roles());
        if (!updatedAt.equals(user.updatedAt())) {
            updatedAt = user.updatedAt();
        }
    }

    AppUser toDomain() {
        return new AppUser(id, issuer, subject,
                new ExternalUserSnapshot(email, displayName, companySnapshot,
                        organizationSnapshot, hrRolesSnapshot),
                status, roles, createdAt, updatedAt, lastLoginAt, version);
    }
}
