CREATE TABLE oauth_consent (
    id BIGSERIAL PRIMARY KEY,
    principal_account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    registered_client_id BIGINT NOT NULL REFERENCES oauth_client (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_oauth_consent_account_client UNIQUE (principal_account_id, registered_client_id)
);

CREATE TABLE oauth_consent_scope (
    consent_id BIGINT NOT NULL REFERENCES oauth_consent (id) ON DELETE CASCADE,
    scope VARCHAR(100) NOT NULL,
    PRIMARY KEY (consent_id, scope),
    CONSTRAINT ck_oauth_consent_scope_normalized CHECK (scope = btrim(scope) AND scope <> '')
);

ALTER TABLE oauth_subject
    ADD CONSTRAINT uk_oauth_subject_subject_account UNIQUE (subject, account_id);
ALTER TABLE oauth_client
    ADD CONSTRAINT uk_oauth_client_id_company UNIQUE (id, company_id);
ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_id_company UNIQUE (id, company_id);

CREATE TABLE oauth_authorization (
    id VARCHAR(128) PRIMARY KEY,
    registered_client_id BIGINT NOT NULL,
    subject UUID NOT NULL,
    principal_account_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes TEXT NOT NULL,
    attributes JSONB NOT NULL,
    server_state_hash VARCHAR(64),
    authenticated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    revocation_reason VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    id_token_issued_at TIMESTAMPTZ,
    id_token_expires_at TIMESTAMPTZ,
    CONSTRAINT fk_oauth_authorization_subject_account
        FOREIGN KEY (subject, principal_account_id)
        REFERENCES oauth_subject (subject, account_id),
    CONSTRAINT fk_oauth_authorization_client_company
        FOREIGN KEY (registered_client_id, company_id)
        REFERENCES oauth_client (id, company_id),
    CONSTRAINT fk_oauth_authorization_account_company
        FOREIGN KEY (principal_account_id, company_id)
        REFERENCES accounts (id, company_id),
    CONSTRAINT ck_oauth_authorization_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_oauth_authorization_grant_not_blank CHECK (btrim(authorization_grant_type) <> ''),
    CONSTRAINT ck_oauth_authorization_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_oauth_authorization_time_order CHECK (expires_at > created_at),
    CONSTRAINT ck_oauth_authorization_id_token_evidence CHECK (
        (id_token_issued_at IS NULL AND id_token_expires_at IS NULL)
        OR (id_token_issued_at IS NOT NULL AND id_token_expires_at > id_token_issued_at)
    ),
    CONSTRAINT ck_oauth_authorization_server_state_hash CHECK (
        server_state_hash IS NULL OR server_state_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_oauth_authorization_account ON oauth_authorization (principal_account_id);
CREATE INDEX ix_oauth_authorization_client ON oauth_authorization (registered_client_id);
CREATE INDEX ix_oauth_authorization_subject_client ON oauth_authorization (subject, registered_client_id);

CREATE TABLE oauth_authorization_code (
    id BIGSERIAL PRIMARY KEY,
    authorization_id VARCHAR(128) NOT NULL REFERENCES oauth_authorization (id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    redirect_uri VARCHAR(2048) NOT NULL,
    code_challenge VARCHAR(128) NOT NULL,
    code_challenge_method VARCHAR(10) NOT NULL,
    nonce VARCHAR(1024),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT uk_oauth_authorization_code_authorization UNIQUE (authorization_id),
    CONSTRAINT uk_oauth_authorization_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_oauth_authorization_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oauth_authorization_code_redirect CHECK (btrim(redirect_uri) <> ''),
    CONSTRAINT ck_oauth_authorization_code_s256 CHECK (code_challenge_method = 'S256'),
    CONSTRAINT ck_oauth_authorization_code_time_order CHECK (expires_at > issued_at)
);

CREATE UNIQUE INDEX uk_oauth_authorization_server_state_hash
    ON oauth_authorization(server_state_hash) WHERE server_state_hash IS NOT NULL;

CREATE TABLE oauth_access_token (
    id BIGSERIAL PRIMARY KEY,
    authorization_id VARCHAR(128) NOT NULL REFERENCES oauth_authorization (id) ON DELETE CASCADE,
    access_token_hash VARCHAR(64) NOT NULL,
    jti VARCHAR(128) NOT NULL,
    audience VARCHAR(255) NOT NULL,
    authorized_scopes TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_oauth_access_token_hash UNIQUE (access_token_hash),
    CONSTRAINT uk_oauth_access_token_jti UNIQUE (jti),
    CONSTRAINT ck_oauth_access_token_hash CHECK (access_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oauth_access_token_scopes CHECK (btrim(authorized_scopes) <> ''),
    CONSTRAINT ck_oauth_access_token_time_order CHECK (expires_at > issued_at)
);

CREATE INDEX ix_oauth_access_token_authorization ON oauth_access_token (authorization_id);

CREATE TABLE oauth_refresh_token (
    id BIGSERIAL PRIMARY KEY,
    authorization_id VARCHAR(128) NOT NULL REFERENCES oauth_authorization (id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(64) NOT NULL,
    family_id UUID NOT NULL,
    authorized_scopes TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    successor_id BIGINT UNIQUE,
    CONSTRAINT uk_oauth_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT uk_oauth_refresh_token_identity_family_expiry
        UNIQUE (id, family_id, authorization_id, expires_at),
    CONSTRAINT fk_oauth_refresh_token_successor_family_expiry
        FOREIGN KEY (successor_id, family_id, authorization_id, expires_at)
        REFERENCES oauth_refresh_token (id, family_id, authorization_id, expires_at),
    CONSTRAINT ck_oauth_refresh_token_hash CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oauth_refresh_token_scopes CHECK (btrim(authorized_scopes) <> ''),
    CONSTRAINT ck_oauth_refresh_token_time_order CHECK (expires_at > issued_at),
    CONSTRAINT ck_oauth_refresh_token_successor_used CHECK (
        (used_at IS NULL AND successor_id IS NULL)
        OR (used_at IS NOT NULL AND successor_id IS NOT NULL)
    )
);

CREATE INDEX ix_oauth_refresh_token_authorization ON oauth_refresh_token (authorization_id);
CREATE INDEX ix_oauth_refresh_token_family ON oauth_refresh_token (family_id);
