CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    code VARCHAR(100) NOT NULL,
    employee_number VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(100) NOT NULL,
    hired_at DATE NOT NULL,
    workplace VARCHAR(255) NOT NULL,
    profile_image_url VARCHAR(2048),
    position_id BIGINT NOT NULL REFERENCES positions (id),
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_users_company_code_upper ON users (company_id, upper(code));
CREATE UNIQUE INDEX uk_users_company_employee_number ON users (company_id, employee_number);

CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT REFERENCES companies (id),
    user_id BIGINT REFERENCES users (id),
    login_email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounts_owner CHECK (
        (company_id IS NULL AND user_id IS NULL)
        OR (company_id IS NOT NULL AND user_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_accounts_company_email_lower
    ON accounts (company_id, lower(login_email));
CREATE UNIQUE INDEX uk_accounts_system_email_lower
    ON accounts (lower(login_email)) WHERE company_id IS NULL;
CREATE UNIQUE INDEX uk_accounts_user_id ON accounts (user_id);

CREATE TABLE account_roles (
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL,
    PRIMARY KEY (account_id, role)
);
