CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    parent_department_id BIGINT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_departments_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_departments_parent_company FOREIGN KEY (parent_department_id, company_id)
        REFERENCES departments (id, company_id),
    CONSTRAINT ck_departments_not_self_parent CHECK (parent_department_id IS NULL OR parent_department_id <> id)
);

CREATE UNIQUE INDEX uq_department_company_code
    ON departments(company_id, upper(code));

CREATE TABLE department_memberships (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    user_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    role VARCHAR(30) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_memberships_user_company FOREIGN KEY (user_id, company_id)
        REFERENCES users (id, company_id),
    CONSTRAINT fk_memberships_department_company FOREIGN KEY (department_id, company_id)
        REFERENCES departments (id, company_id)
);

CREATE UNIQUE INDEX uq_active_user_department
    ON department_memberships(user_id, department_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX uq_active_primary_membership
    ON department_memberships(user_id) WHERE ended_at IS NULL AND is_primary;
CREATE UNIQUE INDEX uq_active_department_head
    ON department_memberships(department_id) WHERE ended_at IS NULL AND role = 'HEAD';
