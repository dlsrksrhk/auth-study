CREATE TABLE oauth_signing_key (
    id BIGSERIAL PRIMARY KEY,
    kid VARCHAR(128) NOT NULL,
    algorithm VARCHAR(16) NOT NULL,
    encrypted_private_material BYTEA NOT NULL,
    public_jwk TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ,
    CONSTRAINT uk_oauth_signing_key_kid UNIQUE (kid),
    CONSTRAINT ck_oauth_signing_key_kid CHECK (btrim(kid) <> ''),
    CONSTRAINT ck_oauth_signing_key_algorithm CHECK (algorithm = 'RS256'),
    CONSTRAINT ck_oauth_signing_key_public_jwk CHECK (btrim(public_jwk) <> ''),
    CONSTRAINT ck_oauth_signing_key_status CHECK (status IN ('ACTIVE', 'VERIFICATION_ONLY')),
    CONSTRAINT ck_oauth_signing_key_lifecycle CHECK (
        (status = 'ACTIVE' AND retired_at IS NULL)
        OR (status = 'VERIFICATION_ONLY' AND retired_at IS NOT NULL AND retired_at >= activated_at)
    )
);

CREATE UNIQUE INDEX uk_oauth_signing_key_single_active
    ON oauth_signing_key ((status)) WHERE status = 'ACTIVE';
CREATE INDEX ix_oauth_signing_key_verification_retired
    ON oauth_signing_key (retired_at DESC) WHERE status = 'VERIFICATION_ONLY';

CREATE TABLE oauth_protocol_event (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    client_id VARCHAR(128),
    subject UUID,
    account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL,
    company_id BIGINT REFERENCES companies (id) ON DELETE SET NULL,
    authorization_id VARCHAR(128) REFERENCES oauth_authorization (id) ON DELETE SET NULL,
    error_code VARCHAR(100),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_oauth_protocol_event_correlation CHECK (btrim(correlation_id) <> ''),
    CONSTRAINT ck_oauth_protocol_event_type CHECK (btrim(event_type) <> ''),
    CONSTRAINT ck_oauth_protocol_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_oauth_protocol_event_metadata_object CHECK (
        metadata IS NULL OR jsonb_typeof(metadata) = 'object'
    )
);

CREATE INDEX ix_oauth_protocol_event_occurred_at ON oauth_protocol_event (occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_correlation ON oauth_protocol_event (correlation_id, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_client ON oauth_protocol_event (client_id, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_subject ON oauth_protocol_event (subject, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_account ON oauth_protocol_event (account_id, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_company ON oauth_protocol_event (company_id, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_authorization ON oauth_protocol_event (authorization_id, occurred_at DESC);
CREATE INDEX ix_oauth_protocol_event_type_outcome ON oauth_protocol_event (event_type, outcome, occurred_at DESC);
