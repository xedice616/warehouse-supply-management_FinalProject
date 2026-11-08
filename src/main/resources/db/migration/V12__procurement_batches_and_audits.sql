-- Procurement, batch-level inventory, expiration tracking and inventory audits.

-- ------------------------------------------------------------------ purchase orders

ALTER TABLE purchase_order ADD COLUMN rejection_reason VARCHAR(500);
ALTER TABLE purchase_order_line ADD COLUMN notes VARCHAR(500);

CREATE SEQUENCE purchase_order_number_seq;
CREATE SEQUENCE goods_receipt_seq INCREMENT BY 50;

-- One row per receiving request. The idempotency key makes a repeated request return the original receipt
-- instead of receiving the goods a second time.
CREATE TABLE goods_receipt (
    id                   BIGINT       PRIMARY KEY,
    purchase_order_id    BIGINT       NOT NULL REFERENCES purchase_order (id) ON DELETE RESTRICT,
    idempotency_key      VARCHAR(100) NOT NULL,
    stock_transaction_id BIGINT       NOT NULL REFERENCES stock_transaction (id) ON DELETE RESTRICT,
    received_by          BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    received_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_goods_receipt_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_goods_receipt_transaction UNIQUE (stock_transaction_id)
);
CREATE INDEX ix_goods_receipt_purchase_order ON goods_receipt (purchase_order_id);
CREATE INDEX ix_goods_receipt_received_by ON goods_receipt (received_by);
CREATE INDEX ix_purchase_order_order_date ON purchase_order (order_date);

-- ------------------------------------------------------------------ batches

ALTER TABLE stock_batch
    ADD COLUMN unit_cost              NUMERIC(19, 4),
    ADD COLUMN purchase_order_line_id BIGINT REFERENCES purchase_order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_batch_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);
CREATE INDEX ix_stock_batch_purchase_order_line ON stock_batch (purchase_order_line_id)
    WHERE purchase_order_line_id IS NOT NULL;

-- Everything that ever entered a (warehouse, product, batch): the batch's initial quantity in that warehouse.
-- Maintained by the same atomic statements as quantity, and backfilled here from the ledger.
ALTER TABLE inventory_balance ADD COLUMN total_received NUMERIC(19, 4) NOT NULL DEFAULT 0;

UPDATE inventory_balance b
SET total_received = COALESCE((
    SELECT SUM(m.quantity) FROM stock_movement m
    WHERE m.destination_warehouse_id = b.warehouse_id
      AND m.product_id = b.product_id
      AND m.batch_id IS NOT DISTINCT FROM b.batch_id), 0);

ALTER TABLE inventory_balance
    ADD CONSTRAINT ck_inventory_balance_within_received CHECK (quantity <= total_received);

CREATE INDEX ix_inventory_balance_warehouse_product ON inventory_balance (warehouse_id, product_id);

-- ------------------------------------------------------------------ inventory audits (formerly "counts")

ALTER TABLE inventory_count RENAME TO inventory_audit;
ALTER TABLE inventory_count_line RENAME TO inventory_audit_item;
ALTER SEQUENCE inventory_count_seq RENAME TO inventory_audit_seq;
ALTER SEQUENCE inventory_count_line_seq RENAME TO inventory_audit_item_seq;

ALTER TABLE inventory_audit RENAME COLUMN count_number TO audit_number;
ALTER TABLE inventory_audit_item RENAME COLUMN inventory_count_id TO inventory_audit_id;
ALTER TABLE inventory_audit_item RENAME COLUMN system_quantity TO expected_quantity;
ALTER TABLE inventory_audit_item RENAME COLUMN variance TO discrepancy;
ALTER TABLE inventory_audit_item RENAME COLUMN variance_reason TO notes;

ALTER TABLE inventory_audit
    DROP CONSTRAINT ck_inventory_count_status,
    DROP CONSTRAINT ck_inventory_count_started_states,
    DROP CONSTRAINT ck_inventory_count_submitted_states,
    DROP CONSTRAINT ck_inventory_count_approved_state,
    DROP CONSTRAINT ck_inventory_count_adjustment_state;
DROP INDEX uq_inventory_count_active_full;

UPDATE inventory_audit SET status = 'PENDING_APPROVAL' WHERE status = 'SUBMITTED';

ALTER TABLE inventory_audit
    ADD COLUMN completed_by     BIGINT REFERENCES app_user (id) ON DELETE RESTRICT,
    ADD COLUMN completed_at     TIMESTAMPTZ,
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD CONSTRAINT ck_inventory_audit_status CHECK (status IN
        ('DRAFT', 'IN_PROGRESS', 'PENDING_APPROVAL', 'APPROVED', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT ck_inventory_audit_completed_pair CHECK ((completed_by IS NULL) = (completed_at IS NULL)),
    ADD CONSTRAINT ck_inventory_audit_started_states CHECK (
        status NOT IN ('IN_PROGRESS', 'PENDING_APPROVAL', 'APPROVED', 'COMPLETED') OR started_at IS NOT NULL),
    ADD CONSTRAINT ck_inventory_audit_submitted_states CHECK (
        status NOT IN ('PENDING_APPROVAL', 'APPROVED', 'COMPLETED') OR submitted_at IS NOT NULL),
    ADD CONSTRAINT ck_inventory_audit_approved_states CHECK (
        (status IN ('APPROVED', 'COMPLETED')) = (approved_at IS NOT NULL)),
    ADD CONSTRAINT ck_inventory_audit_completed_state CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    ADD CONSTRAINT ck_inventory_audit_adjustment_state CHECK (adjustment_transaction_id IS NULL OR status = 'COMPLETED');

-- At most one full audit open per warehouse.
CREATE UNIQUE INDEX uq_inventory_audit_active_full ON inventory_audit (warehouse_id)
    WHERE scope = 'FULL' AND status IN ('IN_PROGRESS', 'PENDING_APPROVAL', 'APPROVED');
CREATE INDEX ix_inventory_audit_created_at ON inventory_audit (created_at);
CREATE SEQUENCE inventory_audit_number_seq;
