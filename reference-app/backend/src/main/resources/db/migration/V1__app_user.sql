CREATE TABLE app_user (
 id uuid PRIMARY KEY,
 issuer varchar(1024) COLLATE "C" NOT NULL CHECK (length(btrim(issuer)) > 0),
 subject varchar(255) COLLATE "C" NOT NULL CHECK (length(btrim(subject)) > 0),
 email text,
 display_name text,
 company_snapshot jsonb CHECK (jsonb_typeof(company_snapshot) = 'object'),
 organization_snapshot jsonb CHECK (jsonb_typeof(organization_snapshot) = 'object'),
 hr_roles_snapshot jsonb NOT NULL CHECK (jsonb_typeof(hr_roles_snapshot) = 'array'),
 status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 last_login_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 CONSTRAINT uk_app_user_identity UNIQUE (issuer,subject)
);

CREATE TABLE app_user_role (
 app_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 role varchar(16) NOT NULL CHECK (role IN ('APP_USER','APP_ADMIN')),
 PRIMARY KEY (app_user_id,role)
);

CREATE TABLE app_bootstrap_state (
 singleton_key smallint PRIMARY KEY CHECK (singleton_key = 1),
 bootstrapped_user_id uuid REFERENCES app_user(id),
 bootstrapped_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 CHECK ((bootstrapped_user_id IS NULL) = (bootstrapped_at IS NULL))
);

INSERT INTO app_bootstrap_state(singleton_key) VALUES (1);
