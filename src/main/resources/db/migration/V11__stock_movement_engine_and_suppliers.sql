-- Stock movement engine: API vocabulary (INBOUND, OUTBOUND, TRANSFER, ADJUSTMENT, WRITE_OFF),
-- external reference numbers and supplier links on stock documents; supplier descriptions.

ALTER TABLE stock_transaction DROP CONSTRAINT ck_stock_transaction_type;
ALTER TABLE stock_movement DROP CONSTRAINT ck_stock_movement_endpoints;

-- Renaming type codes is a schema change, not an edit of business history: quantities, warehouses, users and
-- times are untouched. The append-only triggers are suspended for these statements only.
ALTER TABLE stock_transaction DISABLE TRIGGER trg_stock_transaction_append_only;
ALTER TABLE stock_movement DISABLE TRIGGER trg_stock_movement_append_only;

UPDATE stock_transaction SET type = 'INBOUND' WHERE type = 'RECEIPT';
UPDATE stock_transaction SET type = 'OUTBOUND' WHERE type = 'ISSUE';
UPDATE stock_movement SET movement_type = 'INBOUND' WHERE movement_type = 'RECEIPT';
UPDATE stock_movement SET movement_type = 'OUTBOUND' WHERE movement_type = 'ISSUE';
UPDATE stock_movement SET movement_type = 'ADJUSTMENT' WHERE movement_type IN ('ADJUSTMENT_IN', 'ADJUSTMENT_OUT');

ALTER TABLE stock_transaction ENABLE TRIGGER trg_stock_transaction_append_only;
ALTER TABLE stock_movement ENABLE TRIGGER trg_stock_movement_append_only;

ALTER TABLE stock_transaction ADD CONSTRAINT ck_stock_transaction_type CHECK (type IN
    ('OPENING_BALANCE', 'INBOUND', 'OUTBOUND', 'TRANSFER', 'ADJUSTMENT', 'WRITE_OFF', 'RETURN_TO_SUPPLIER', 'REVERSAL'));

ALTER TABLE stock_movement ADD CONSTRAINT ck_stock_movement_endpoints CHECK (
    (movement_type IN ('OPENING_BALANCE', 'INBOUND')
        AND source_warehouse_id IS NULL AND destination_warehouse_id IS NOT NULL)
    OR (movement_type IN ('OUTBOUND', 'WRITE_OFF', 'RETURN_TO_SUPPLIER')
        AND source_warehouse_id IS NOT NULL AND destination_warehouse_id IS NULL)
    OR (movement_type = 'ADJUSTMENT'
        AND num_nonnulls(source_warehouse_id, destination_warehouse_id) = 1)
    OR (movement_type = 'TRANSFER'
        AND source_warehouse_id IS NOT NULL AND destination_warehouse_id IS NOT NULL
        AND source_warehouse_id <> destination_warehouse_id)
    OR (movement_type = 'REVERSAL'
        AND (source_warehouse_id IS NOT NULL OR destination_warehouse_id IS NOT NULL)
        AND source_warehouse_id IS DISTINCT FROM destination_warehouse_id));

ALTER TABLE stock_transaction
    ADD COLUMN reference_number VARCHAR(64),
    ADD COLUMN supplier_id      BIGINT REFERENCES supplier (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_transaction_supplier CHECK (supplier_id IS NULL OR type IN ('INBOUND', 'RETURN_TO_SUPPLIER'));

CREATE INDEX ix_stock_transaction_supplier ON stock_transaction (supplier_id) WHERE supplier_id IS NOT NULL;
CREATE INDEX ix_stock_transaction_reference ON stock_transaction (reference_number) WHERE reference_number IS NOT NULL;
CREATE INDEX ix_stock_movement_type_time ON stock_movement (movement_type, performed_at);

-- Human-readable numbers: SM-2026-000001 for movements, SUP-000001 for suppliers.
CREATE SEQUENCE stock_movement_number_seq;
CREATE SEQUENCE supplier_code_seq;

ALTER TABLE supplier ADD COLUMN description VARCHAR(1000);
