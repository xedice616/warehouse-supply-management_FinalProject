-- Catalog: units of measure, product categories and products.
-- Products carry no stock quantity; stock lives only in the inventory tables.

CREATE SEQUENCE unit_of_measure_seq INCREMENT BY 50;
CREATE SEQUENCE product_category_seq INCREMENT BY 50;
CREATE SEQUENCE product_seq INCREMENT BY 50;

CREATE TABLE unit_of_measure (
    id            BIGINT       PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    -- Decimal places allowed for quantities in this unit (PCS = 0, KG = 3).
    -- Quantity columns are NUMERIC(19,4), so 4 is the maximum.
    decimal_scale INTEGER      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(100) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(100) NOT NULL,
    CONSTRAINT uq_unit_of_measure_code UNIQUE (code),
    CONSTRAINT ck_unit_of_measure_code_format CHECK (code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_unit_of_measure_scale CHECK (decimal_scale BETWEEN 0 AND 4)
);

CREATE TABLE product_category (
    id         BIGINT       PRIMARY KEY,
    parent_id  BIGINT       REFERENCES product_category (id) ON DELETE RESTRICT,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    version    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    CONSTRAINT ck_product_category_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
-- Sibling names are unique ignoring case; root categories (parent_id NULL) count as siblings too.
CREATE UNIQUE INDEX uq_product_category_sibling_name
    ON product_category (COALESCE(parent_id, 0), lower(name));
CREATE INDEX ix_product_category_parent ON product_category (parent_id);

CREATE TABLE product (
    id             BIGINT        PRIMARY KEY,
    sku            VARCHAR(64)   NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    description    VARCHAR(2000),
    barcode        VARCHAR(64),
    category_id    BIGINT        NOT NULL REFERENCES product_category (id) ON DELETE RESTRICT,
    uom_id         BIGINT        NOT NULL REFERENCES unit_of_measure (id) ON DELETE RESTRICT,
    batch_tracked  BOOLEAN       NOT NULL DEFAULT FALSE,
    expiry_tracked BOOLEAN       NOT NULL DEFAULT FALSE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     VARCHAR(100)  NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    updated_by     VARCHAR(100)  NOT NULL,
    -- SKUs are stored upper-case, so the unique constraint is case-insensitive.
    CONSTRAINT uq_product_sku UNIQUE (sku),
    CONSTRAINT uq_product_barcode UNIQUE (barcode),
    CONSTRAINT ck_product_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]*$'),
    -- Expiry dates live on batches, so an expiry-tracked product must be batch-tracked.
    CONSTRAINT ck_product_expiry_requires_batch CHECK (NOT expiry_tracked OR batch_tracked)
);
CREATE INDEX ix_product_category ON product (category_id);
CREATE INDEX ix_product_uom ON product (uom_id);
-- Case-insensitive prefix search on name (WHERE lower(name) LIKE 'abc%').
CREATE INDEX ix_product_name_prefix ON product (lower(name) text_pattern_ops);

INSERT INTO unit_of_measure (id, code, name, decimal_scale, version, created_at, created_by, updated_at, updated_by)
SELECT nextval('unit_of_measure_seq'), v.code, v.name, v.decimal_scale, 0, now(), 'system', now(), 'system'
FROM (VALUES
          ('PCS', 'Piece', 0),
          ('BOX', 'Box', 0),
          ('PACK', 'Pack', 0),
          ('KG', 'Kilogram', 3),
          ('G', 'Gram', 0),
          ('L', 'Litre', 3),
          ('M', 'Metre', 3)
     ) AS v (code, name, decimal_scale);
