-- Warehouses, and which warehouses each user may operate on.
-- Warehouses carry no stock quantity; stock lives only in the inventory tables.

CREATE SEQUENCE warehouse_seq INCREMENT BY 50;

CREATE TABLE warehouse (
    id            BIGINT       PRIMARY KEY,
    code          VARCHAR(20)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    address_line  VARCHAR(255),
    city          VARCHAR(100),
    postal_code   VARCHAR(20),
    country_code  VARCHAR(2),
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(100) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(100) NOT NULL,
    CONSTRAINT uq_warehouse_code UNIQUE (code),
    CONSTRAINT ck_warehouse_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    CONSTRAINT ck_warehouse_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_warehouse_country_code CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$')
);

-- Data scope: a non-admin user may only operate on the warehouses listed here.
CREATE TABLE user_warehouse_access (
    user_id      BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    warehouse_id BIGINT NOT NULL REFERENCES warehouse (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, warehouse_id)
);
CREATE INDEX ix_user_warehouse_access_warehouse ON user_warehouse_access (warehouse_id);
