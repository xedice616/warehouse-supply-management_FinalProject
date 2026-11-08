-- Suppliers.

CREATE SEQUENCE supplier_seq INCREMENT BY 50;

CREATE TABLE supplier (
    id                 BIGINT       PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL,
    name               VARCHAR(200) NOT NULL,
    -- Taxpayer identification number (VÖEN in Azerbaijan); optional for foreign suppliers.
    tax_id             VARCHAR(20),
    contact_person     VARCHAR(150),
    email              VARCHAR(254),
    phone              VARCHAR(30),
    address_line       VARCHAR(255),
    city               VARCHAR(100),
    postal_code        VARCHAR(20),
    country_code       VARCHAR(2),
    payment_terms_days INTEGER      NOT NULL DEFAULT 0,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    version            BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(100) NOT NULL,
    CONSTRAINT uq_supplier_code UNIQUE (code),
    CONSTRAINT uq_supplier_tax_id UNIQUE (tax_id),
    CONSTRAINT ck_supplier_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    CONSTRAINT ck_supplier_email_lower CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_supplier_country_code CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_supplier_payment_terms CHECK (payment_terms_days BETWEEN 0 AND 365)
);
CREATE INDEX ix_supplier_name_prefix ON supplier (lower(name) text_pattern_ops);
