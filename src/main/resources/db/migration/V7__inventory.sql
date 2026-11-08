-- Inventory: batches, the immutable stock ledger, and the balance projection.
--
--   stock_transaction  one posted document (receipt, issue, transfer, ...), append-only
--   stock_movement     its lines; each moves one product/batch out of and/or into a warehouse, append-only
--   inventory_balance  quantity on hand per (warehouse, product, batch), kept equal to the sum of the ledger
--                      in the same database transaction as every movement insert
--
-- Documents that cause stock changes point at their stock_transaction (goods receipts, inventory counts),
-- so the ledger itself has no loosely typed "reference" columns.

CREATE SEQUENCE stock_batch_seq INCREMENT BY 50;
CREATE SEQUENCE stock_transaction_seq INCREMENT BY 50;
CREATE SEQUENCE stock_movement_seq INCREMENT BY 50;
CREATE SEQUENCE inventory_balance_seq INCREMENT BY 50;

-- ------------------------------------------------------------------ batches (lots)

CREATE TABLE stock_batch (
    id              BIGINT       PRIMARY KEY,
    product_id      BIGINT       NOT NULL REFERENCES product (id) ON DELETE RESTRICT,
    batch_number    VARCHAR(64)  NOT NULL,
    manufactured_on DATE,
    expires_on      DATE,
    supplier_id     BIGINT       REFERENCES supplier (id) ON DELETE RESTRICT,
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(100) NOT NULL,
    CONSTRAINT uq_stock_batch_number UNIQUE (product_id, batch_number),
    -- Target for composite foreign keys that guarantee "this batch belongs to this product".
    CONSTRAINT uq_stock_batch_product UNIQUE (id, product_id),
    CONSTRAINT ck_stock_batch_dates CHECK (expires_on IS NULL OR manufactured_on IS NULL OR expires_on > manufactured_on)
);
CREATE INDEX ix_stock_batch_expires_on ON stock_batch (expires_on) WHERE expires_on IS NOT NULL;
CREATE INDEX ix_stock_batch_supplier ON stock_batch (supplier_id);

-- ------------------------------------------------------------------ ledger: transactions

CREATE TABLE stock_transaction (
    id                 BIGINT       PRIMARY KEY,
    transaction_number VARCHAR(32)  NOT NULL,
    type               VARCHAR(32)  NOT NULL,
    reason             VARCHAR(500),
    reversal_of_id     BIGINT       REFERENCES stock_transaction (id) ON DELETE RESTRICT,
    idempotency_key    VARCHAR(100),
    performed_by       BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    performed_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_stock_transaction_number UNIQUE (transaction_number),
    -- A transaction can be reversed at most once.
    CONSTRAINT uq_stock_transaction_reversal_of UNIQUE (reversal_of_id),
    CONSTRAINT uq_stock_transaction_idempotency_key UNIQUE (idempotency_key),
    -- Target for the composite foreign key that keeps each movement's user/time equal to its transaction's.
    CONSTRAINT uq_stock_transaction_actor UNIQUE (id, performed_by, performed_at),
    CONSTRAINT ck_stock_transaction_type CHECK (type IN
        ('OPENING_BALANCE', 'RECEIPT', 'ISSUE', 'TRANSFER', 'WRITE_OFF', 'RETURN_TO_SUPPLIER', 'ADJUSTMENT', 'REVERSAL')),
    CONSTRAINT ck_stock_transaction_reversal CHECK ((type = 'REVERSAL') = (reversal_of_id IS NOT NULL)),
    CONSTRAINT ck_stock_transaction_not_self_reversal CHECK (reversal_of_id IS NULL OR reversal_of_id <> id)
);
CREATE INDEX ix_stock_transaction_performed_at ON stock_transaction (performed_at);
CREATE INDEX ix_stock_transaction_performed_by ON stock_transaction (performed_by);
CREATE INDEX ix_stock_transaction_type_time ON stock_transaction (type, performed_at);

-- ------------------------------------------------------------------ ledger: movements

CREATE TABLE stock_movement (
    id                        BIGINT         PRIMARY KEY,
    transaction_id            BIGINT         NOT NULL,
    line_no                   INTEGER        NOT NULL,
    movement_type             VARCHAR(32)    NOT NULL,
    product_id                BIGINT         NOT NULL REFERENCES product (id) ON DELETE RESTRICT,
    batch_id                  BIGINT,
    quantity                  NUMERIC(19, 4) NOT NULL,
    source_warehouse_id       BIGINT         REFERENCES warehouse (id) ON DELETE RESTRICT,
    destination_warehouse_id  BIGINT         REFERENCES warehouse (id) ON DELETE RESTRICT,
    -- Balance of each affected (warehouse, product, batch) right after this movement: the stock card.
    source_balance_after      NUMERIC(19, 4),
    destination_balance_after NUMERIC(19, 4),
    unit_cost                 NUMERIC(19, 4),
    performed_by              BIGINT         NOT NULL,
    performed_at              TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_stock_movement_line UNIQUE (transaction_id, line_no),
    CONSTRAINT fk_stock_movement_transaction FOREIGN KEY (transaction_id, performed_by, performed_at)
        REFERENCES stock_transaction (id, performed_by, performed_at) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_batch FOREIGN KEY (batch_id, product_id)
        REFERENCES stock_batch (id, product_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_movement_line_no CHECK (line_no > 0),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT ck_stock_movement_balances CHECK (
        (source_balance_after IS NULL OR source_balance_after >= 0)
        AND (destination_balance_after IS NULL OR destination_balance_after >= 0)
        AND ((source_warehouse_id IS NULL) = (source_balance_after IS NULL))
        AND ((destination_warehouse_id IS NULL) = (destination_balance_after IS NULL))),
    -- Which warehouses a movement touches is fixed by its type.
    CONSTRAINT ck_stock_movement_endpoints CHECK (
        (movement_type IN ('OPENING_BALANCE', 'RECEIPT', 'ADJUSTMENT_IN')
            AND source_warehouse_id IS NULL AND destination_warehouse_id IS NOT NULL)
        OR (movement_type IN ('ISSUE', 'WRITE_OFF', 'RETURN_TO_SUPPLIER', 'ADJUSTMENT_OUT')
            AND source_warehouse_id IS NOT NULL AND destination_warehouse_id IS NULL)
        OR (movement_type = 'TRANSFER'
            AND source_warehouse_id IS NOT NULL AND destination_warehouse_id IS NOT NULL
            AND source_warehouse_id <> destination_warehouse_id)
        OR (movement_type = 'REVERSAL'
            AND (source_warehouse_id IS NOT NULL OR destination_warehouse_id IS NOT NULL)
            AND source_warehouse_id IS DISTINCT FROM destination_warehouse_id))
);
-- Stock card and history queries: "movements of product P in or out of warehouse W, in time order".
CREATE INDEX ix_stock_movement_source ON stock_movement (source_warehouse_id, product_id, performed_at)
    WHERE source_warehouse_id IS NOT NULL;
CREATE INDEX ix_stock_movement_destination ON stock_movement (destination_warehouse_id, product_id, performed_at)
    WHERE destination_warehouse_id IS NOT NULL;
CREATE INDEX ix_stock_movement_product_time ON stock_movement (product_id, performed_at);
CREATE INDEX ix_stock_movement_batch ON stock_movement (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX ix_stock_movement_performed_at ON stock_movement (performed_at);
CREATE INDEX ix_stock_movement_actor ON stock_movement (transaction_id, performed_by, performed_at);

-- ------------------------------------------------------------------ balance projection

CREATE TABLE inventory_balance (
    id           BIGINT         PRIMARY KEY,
    warehouse_id BIGINT         NOT NULL REFERENCES warehouse (id) ON DELETE RESTRICT,
    product_id   BIGINT         NOT NULL REFERENCES product (id) ON DELETE RESTRICT,
    batch_id     BIGINT,
    quantity     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    version      BIGINT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- One row per key; products without batches use batch_id NULL, which must also be unique (PostgreSQL 15+).
    CONSTRAINT uq_inventory_balance_key UNIQUE NULLS NOT DISTINCT (warehouse_id, product_id, batch_id),
    CONSTRAINT fk_inventory_balance_batch FOREIGN KEY (batch_id, product_id)
        REFERENCES stock_batch (id, product_id) ON DELETE RESTRICT,
    -- Last line of defense: stock can never be negative, whatever the application does.
    CONSTRAINT ck_inventory_balance_non_negative CHECK (quantity >= 0)
);
CREATE INDEX ix_inventory_balance_product ON inventory_balance (product_id);
CREATE INDEX ix_inventory_balance_batch ON inventory_balance (batch_id) WHERE batch_id IS NOT NULL;

-- ------------------------------------------------------------------ immutability of the ledger

CREATE TRIGGER trg_stock_transaction_append_only
    BEFORE UPDATE OR DELETE ON stock_transaction
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_stock_transaction_no_truncate
    BEFORE TRUNCATE ON stock_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_stock_movement_append_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_stock_movement_no_truncate
    BEFORE TRUNCATE ON stock_movement
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

-- The application role may only read and append to the ledger, and may never delete balance rows.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_db_role}')
       AND '${app_db_role}' <> current_user THEN
        EXECUTE format('REVOKE UPDATE, DELETE ON stock_transaction, stock_movement FROM %I', '${app_db_role}');
        EXECUTE format('REVOKE DELETE ON inventory_balance FROM %I', '${app_db_role}');
    END IF;
END
$$;
