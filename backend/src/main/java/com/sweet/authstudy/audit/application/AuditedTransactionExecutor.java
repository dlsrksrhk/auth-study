package com.sweet.authstudy.audit.application;

import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.trace.TraceIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class AuditedTransactionExecutor {
    private static final Logger log = LoggerFactory.getLogger(AuditedTransactionExecutor.class);

    private final AuditService auditService;
    private final TraceIdProvider traceIdProvider;
    private final TransactionTemplate transactions;

    public AuditedTransactionExecutor(AuditService auditService, TraceIdProvider traceIdProvider,
                                      PlatformTransactionManager transactionManager) {
        this.auditService = auditService;
        this.traceIdProvider = traceIdProvider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public <T> T execute(AuditFailurePlan failurePlan, Supplier<T> businessOperation) {
        try {
            return transactions.execute(status -> businessOperation.get());
        } catch (ApiException businessFailure) {
            recordFailure(failurePlan, businessFailure);
            throw businessFailure;
        }
    }

    private void recordFailure(AuditFailurePlan plan, ApiException businessFailure) {
        if (!plan.identified()) return;
        try {
            auditService.recordFailure(plan.actor(), plan.action(), plan.targetType(),
                    plan.targetId(), plan.companyId(), plan.safeDetails(), businessFailure);
        } catch (RuntimeException auditFailure) {
            log.error("Failure audit write failed: action={}, targetType={}, targetId={}, traceId={}",
                    plan.action(), plan.targetType(), plan.targetId(), traceIdProvider.current(), auditFailure);
        }
    }
}
