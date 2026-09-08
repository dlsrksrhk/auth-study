package com.sweet.referenceapp.user.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.referenceapp.user.domain.AppUser;
import com.sweet.referenceapp.user.domain.AppUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AppUserRepositoryAdapter implements AppUserRepository {
    private final EntityManager entityManager;
    private final AppUserJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public AppUserRepositoryAdapter(EntityManager entityManager, AppUserJpaRepository jpaRepository,
            ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean insertIfAbsent(AppUser candidate) {
        var snapshot = candidate.snapshot();
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO app_user
                 (id,issuer,subject,email,display_name,company_snapshot,organization_snapshot,
                  hr_roles_snapshot,status,created_at,updated_at,last_login_at,version)
                VALUES (:id,:issuer,:subject,:email,:displayName,cast(:company as jsonb),
                 cast(:organization as jsonb),cast(:hrRoles as jsonb),'ACTIVE',:now,:now,:now,0)
                ON CONFLICT (issuer,subject) DO NOTHING
                """)
                .setParameter("id", candidate.id())
                .setParameter("issuer", candidate.issuer())
                .setParameter("subject", candidate.subject())
                .setParameter("email", snapshot.email())
                .setParameter("displayName", snapshot.displayName())
                .setParameter("company", json(snapshot.company()))
                .setParameter("organization", json(snapshot.organization()))
                .setParameter("hrRoles", json(snapshot.hrRoles()))
                .setParameter("now", candidate.createdAt())
                .executeUpdate();
        if (inserted == 1) {
            entityManager.createNativeQuery(
                    "INSERT INTO app_user_role(app_user_id,role) VALUES (:id,'APP_USER')")
                    .setParameter("id", candidate.id())
                    .executeUpdate();
            return true;
        }
        return false;
    }

    @Override
    public Optional<AppUser> findByIdentityForUpdate(String issuer, String subject) {
        return jpaRepository.findLocked(issuer, subject).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return entity.toDomain();
        });
    }

    @Override
    public AppUser updateSnapshot(AppUser user) {
        var entity = jpaRepository.findLocked(user.issuer(), user.subject())
                .orElseThrow(() -> new IllegalStateException("App user does not exist"));
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        if (entity.version() != user.version()) {
            throw new ObjectOptimisticLockingFailureException(AppUserJpaEntity.class, user.id());
        }
        entity.replaceSnapshot(user.snapshot(), user.updatedAt(), user.lastLoginAt());
        entityManager.flush();
        return entity.toDomain();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Snapshot cannot be encoded", exception);
        }
    }
}
