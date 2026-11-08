-- Identity: users and roles.
-- Users are never hard-deleted (stock movements reference them forever); they are disabled instead.

CREATE SEQUENCE role_seq INCREMENT BY 50;
CREATE SEQUENCE app_user_seq INCREMENT BY 50;

-- Reference data: a fixed set of roles seeded below. The application doesn't create roles.
CREATE TABLE role (
    id          BIGINT       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    CONSTRAINT uq_role_code UNIQUE (code),
    CONSTRAINT ck_role_code_format CHECK (code ~ '^[A-Z][A-Z_]*$')
);

-- "user" is a reserved word in PostgreSQL, hence app_user.
CREATE TABLE app_user (
    id                    BIGINT       PRIMARY KEY,
    username              VARCHAR(50)  NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(150) NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    version               BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(100) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(100) NOT NULL,
    -- Usernames and emails are stored lower-case, so plain unique constraints are case-insensitive.
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,49}$'),
    CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_app_user_failed_attempts CHECK (failed_login_attempts >= 0)
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_role_role ON user_role (role_id);

INSERT INTO role (id, code, description) VALUES
    (nextval('role_seq'), 'ADMIN',             'Full access, including users and system configuration'),
    (nextval('role_seq'), 'WAREHOUSE_MANAGER', 'Manages warehouses, approves counts, write-offs and purchase orders'),
    (nextval('role_seq'), 'STOREKEEPER',       'Receives, issues and transfers stock in assigned warehouses'),
    (nextval('role_seq'), 'PURCHASER',         'Manages suppliers and purchase orders'),
    (nextval('role_seq'), 'AUDITOR',           'Performs inventory counts'),
    (nextval('role_seq'), 'VIEWER',            'Read-only access to stock and reports');
