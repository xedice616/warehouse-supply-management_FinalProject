-- CSV bulk import bookkeeping and a few report/search indexes.

-- ------------------------------------------------------------------ CSV imports
-- One row per upload. The idempotency key makes a repeated upload return the original result instead of
-- importing the goods a second time. The full result (per-row errors included) is kept as JSON so a replay
-- is faithful without reprocessing the file.
CREATE SEQUENCE stock_import_seq INCREMENT BY 50;

CREATE TABLE stock_import (
    id                 BIGINT        PRIMARY KEY,
    idempotency_key    VARCHAR(100)  NOT NULL,
    total_rows         INTEGER       NOT NULL,
    successful_rows    INTEGER       NOT NULL,
    failed_rows        INTEGER       NOT NULL,
    imported_quantity  NUMERIC(19, 4) NOT NULL,
    result_json        TEXT          NOT NULL,
    imported_by        BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    imported_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_stock_import_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_stock_import_counts CHECK (
        total_rows >= 0 AND successful_rows >= 0 AND failed_rows >= 0
        AND successful_rows + failed_rows = total_rows AND imported_quantity >= 0)
);
CREATE INDEX ix_stock_import_imported_by ON stock_import (imported_by);

-- ------------------------------------------------------------------ report/search helpers
-- Supplier performance groups purchase orders by supplier; status is already indexed with the date. The
-- valuation report reads batch unit costs per (warehouse, product); inventory_balance already covers that.
-- Exact barcode lookups use uq_product_barcode. The only gap is counting movements by their actor over a
-- period for the stock-movement report, so index the transaction actor with time.
CREATE INDEX ix_stock_transaction_actor_time ON stock_transaction (performed_by, performed_at);
