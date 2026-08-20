package com.sweet.authstudy.audit.infrastructure;

import com.sweet.authstudy.audit.domain.AuditLog;
import com.sweet.authstudy.audit.domain.AuditLogRepository;
import com.sweet.authstudy.shared.application.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepositoryAdapter implements AuditLogRepository {
    private final AuditLogJpaRepository repository;

    public AuditLogRepositoryAdapter(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuditLog save(AuditLog log) {
        if (log.id() != null) throw new IllegalArgumentException("Audit logs are immutable.");
        return repository.saveAndFlush(AuditLogJpaEntity.from(log)).toDomain();
    }

    @Override
    public PageResult<AuditLog> search(long companyId, String search, String action, Boolean success,
            int page, int size, String sort) {
        Sort requested = Sort.by(Sort.Direction.DESC, property(sort));
        Sort stable = sort.equals("id") ? requested : requested.and(Sort.by(Sort.Direction.DESC, "id"));
        var result = repository.search(companyId, search == null ? "" : search,
                action, success, PageRequest.of(page, size, stable));
        return new PageResult<>(result.getContent().stream().map(AuditLogJpaEntity::toDomain).toList(),
                result.getTotalElements(), result.getTotalPages());
    }

    private String property(String sort) {
        return switch (sort) {
            case "occurredAt" -> "occurredAt";
            case "action" -> "action";
            case "success" -> "success";
            case "actorAccountId" -> "actorAccountId";
            case "targetType" -> "targetType";
            default -> throw new IllegalArgumentException("Unsupported audit sort.");
        };
    }
}
