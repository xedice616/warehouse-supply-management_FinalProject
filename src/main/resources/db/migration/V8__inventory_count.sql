-- Inventory audits (physical stock counts).
-- Approving a count posts one ADJUSTMENT stock transaction, which the count references.

CREATE SEQUENCE inventory_count_seq INCREMENT BY 50;
CREATE SEQUENCE inventory_count_line_seq INCREMENT BY 50;

CREATE TABLE inventory_count (
    id                        BIGINT        PRIMARY KEY,
    count_number              VARCHAR(32)   NOT NULL,
    warehouse_id              BIGINT        NOT NULL REFERENCES warehouse (id) ON DELETE RESTRICT,
    scope                     VARCHAR(20)   NOT NULL,
    status                    VARCHAR(20)   NOT NULL,
    -- Blind count: counters don't see the system quantity.
    blind                     BOOLEAN       NOT NULL DEFAULT TRUE,
    notes                     VARCHAR(1000),
    started_by                BIGINT        REFERENCES app_user (id) ON DELETE RESTRICT,
    started_at                TIMESTAMPTZ,
    submitted_by              BIGINT        REFERENCES app_user (id) ON DELETE RESTRICT,
    submitted_at              TIMESTAMPTZ,
    approved_by               BIGINT        REFERENCES app_user (id) ON DELETE RESTRICT,
    approved_at               TIMESTAMPTZ,
    adjustment_transaction_id BIGINT        REFERENCES stock_transaction (id) ON DELETE RESTRICT,
    version                   BIGINT        NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    created_by                VARCHAR(100)  NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    updated_by                VARCHAR(100)  NOT NULL,
    CONSTRAINT uq_inventory_count_number UNIQUE (count_number),
    CONSTRAINT uq_inventory_count_adjustment UNIQUE (adjustment_transaction_id),
    CONSTRAINT ck_inventory_count_scope CHECK (scope IN ('FULL', 'PARTIAL')),
    CONSTRAINT ck_inventory_count_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'SUBMITTED', 'APPROVED', 'CANCELLED')),
    -- Each lifecycle step is recorded as a (who, when) pair ...
    CONSTRAINT ck_inventory_count_started_pair CHECK ((started_by IS NULL) = (started_at IS NULL)),
    CONSTRAINT ck_inventory_count_submitted_pair CHECK ((submitted_by IS NULL) = (submitted_at IS NULL)),
    CONSTRAINT ck_inventory_count_approved_pair CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    -- ... and each status requires the steps that lead to it.
    CONSTRAINT ck_inventory_count_started_states CHECK (
        status NOT IN ('IN_PROGRESS', 'SUBMITTED', 'APPROVED') OR started_at IS NOT NULL),
    CONSTRAINT ck_inventory_count_submitted_states CHECK (
        status NOT IN ('SUBMITTED', 'APPROVED') OR submitted_at IS NOT NULL),
    CONSTRAINT ck_inventory_count_approved_state CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    CONSTRAINT ck_inventory_count_adjustment_state CHECK (adjustment_transaction_id IS NULL OR status = 'APPROVED')
);
CREATE INDEX ix_inventory_count_warehouse_status ON inventory_count (warehouse_id, status);
-- At most one full count in progress per warehouse.
CREATE UNIQUE INDEX uq_inventory_count_active_full ON inventory_count (warehouse_id)
    WHERE scope = 'FULL' AND status IN ('IN_PROGRESS', 'SUBMITTED');

CREATE TABLE inventory_count_line (
    id                 BIGINT         PRIMARY KEY,
    inventory_count_id BIGINT         NOT NULL REFERENCES inventory_count (id) ON DELETE CASCADE,
    product_id         BIGINT         NOT NULL REFERENCES product (id) ON DELETE RESTRICT,
    batch_id           BIGINT,
    -- System quantity snapshot taken when the count starts.
    system_quantity    NUMERIC(19, 4) NOT NULL,
    counted_quantity   NUMERIC(19, 4),
    variance           NUMERIC(19, 4) GENERATED ALWAYS AS (counted_quantity - system_quantity) STORED,
    variance_reason    VARCHAR(500),
    counted_by         BIGINT         REFERENCES app_user (id) ON DELETE RESTRICT,
    counted_at         TIMESTAMPTZ,
    CONSTRAINT uq_inventory_count_line_key UNIQUE NULLS NOT DISTINCT (inventory_count_id, product_id, batch_id),
    CONSTRAINT fk_inventory_count_line_batch FOREIGN KEY (batch_id, product_id)
        REFERENCES stock_batch (id, product_id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_count_line_system CHECK (system_quantity >= 0),
    CONSTRAINT ck_inventory_count_line_counted CHECK (counted_quantity IS NULL OR counted_quantity >= 0),
    -- A count is recorded with who counted and when, or not at all.
    CONSTRAINT ck_inventory_count_line_counted_triple CHECK (
        (counted_quantity IS NULL) = (counted_by IS NULL) AND (counted_by IS NULL) = (counted_at IS NULL))
);
CREATE INDEX ix_inventory_count_line_product ON inventory_count_line (product_id);
CREATE INDEX ix_inventory_count_line_batch ON inventory_count_line (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX ix_inventory_count_line_counted_by ON inventory_count_line (counted_by);
