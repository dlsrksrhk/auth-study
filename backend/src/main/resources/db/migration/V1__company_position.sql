CREATE TABLE companies (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email_domain VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_companies_code_upper ON companies (upper(code));
CREATE UNIQUE INDEX uk_companies_email_domain_lower ON companies (lower(email_domain));

CREATE TABLE positions (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    level INTEGER NOT NULL,
    display_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_positions_company_code_upper ON positions (company_id, upper(code));
