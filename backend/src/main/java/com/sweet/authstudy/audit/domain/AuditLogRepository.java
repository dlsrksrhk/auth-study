package com.sweet.authstudy.audit.domain;

import com.sweet.authstudy.shared.application.PageResult;

public interface AuditLogRepository {
    AuditLog save(AuditLog log);

    PageResult<AuditLog> search(
            long companyId, String search, String action, Boolean success,
            int page, int size, String sort);
}
