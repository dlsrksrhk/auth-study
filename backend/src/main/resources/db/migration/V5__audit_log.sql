CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_account_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NOT NULL,
    company_id BIGINT,
    success BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    trace_id TEXT NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_audit_logs_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_logs_target_type_not_blank CHECK (btrim(target_type) <> ''),
    CONSTRAINT ck_audit_logs_trace_id_not_blank CHECK (btrim(trace_id) <> ''),
    CONSTRAINT ck_audit_logs_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_audit_logs_company_occurred
    ON audit_logs (company_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_logs_company_action
    ON audit_logs (company_id, action, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_logs_actor_occurred
    ON audit_logs (actor_account_id, occurred_at DESC, id DESC);
