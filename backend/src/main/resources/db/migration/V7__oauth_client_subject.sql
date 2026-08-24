CREATE TABLE oauth_subject (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    subject UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_oauth_subject_account UNIQUE (account_id),
    CONSTRAINT uk_oauth_subject_subject UNIQUE (subject)
);

CREATE TABLE oauth_client (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    client_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    trust VARCHAR(30) NOT NULL,
    public_client BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_oauth_client_client_id UNIQUE (client_id),
    CONSTRAINT ck_oauth_client_client_id_not_blank CHECK (btrim(client_id) <> ''),
    CONSTRAINT ck_oauth_client_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE oauth_client_secret (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES oauth_client (id) ON DELETE CASCADE,
    secret_hash VARCHAR(100) NOT NULL,
    secret_hint VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_oauth_client_secret_hash_not_blank CHECK (btrim(secret_hash) <> '')
);

CREATE UNIQUE INDEX uk_oauth_client_secret_active
    ON oauth_client_secret (client_id) WHERE revoked_at IS NULL;

CREATE TABLE oauth_client_redirect_uri (
    client_id BIGINT NOT NULL REFERENCES oauth_client (id) ON DELETE CASCADE,
    redirect_uri VARCHAR(2048) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    CONSTRAINT uk_oauth_client_redirect UNIQUE (client_id, redirect_uri),
    CONSTRAINT ck_oauth_client_redirect_not_blank CHECK (btrim(redirect_uri) <> ''),
    CONSTRAINT ck_oauth_client_redirect_purpose CHECK (purpose IN ('AUTHORIZATION', 'POST_LOGOUT'))
);

CREATE TABLE oauth_client_scope (
    client_id BIGINT NOT NULL REFERENCES oauth_client (id) ON DELETE CASCADE,
    scope VARCHAR(50) NOT NULL,
    CONSTRAINT uk_oauth_client_scope UNIQUE (client_id, scope),
    CONSTRAINT ck_oauth_client_scope_not_blank CHECK (btrim(scope) <> '')
);
