-- Purchase orders and their lines (items).
-- An order's total is derived from its lines, not stored, so it can never disagree with them.
-- received_quantity changes only when goods are received (purchasing stage), never through the order API.

CREATE SEQUENCE purchase_order_seq INCREMENT BY 50;
CREATE SEQUENCE purchase_order_line_seq INCREMENT BY 50;

CREATE TABLE purchase_order (
    id                     BIGINT        PRIMARY KEY,
    po_number              VARCHAR(32)   NOT NULL,
    supplier_id            BIGINT        NOT NULL REFERENCES supplier (id) ON DELETE RESTRICT,
    warehouse_id           BIGINT        NOT NULL REFERENCES warehouse (id) ON DELETE RESTRICT,
    status                 VARCHAR(20)   NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    order_date             DATE          NOT NULL,
    expected_delivery_date DATE,
    notes                  VARCHAR(1000),
    approved_by            BIGINT        REFERENCES app_user (id) ON DELETE RESTRICT,
    approved_at            TIMESTAMPTZ,
    cancellation_reason    VARCHAR(500),
    version                BIGINT        NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL,
    created_by             VARCHAR(100)  NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(100)  NOT NULL,
    CONSTRAINT uq_purchase_order_number UNIQUE (po_number),
    CONSTRAINT ck_purchase_order_status CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_purchase_order_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_purchase_order_delivery_date CHECK (expected_delivery_date IS NULL OR expected_delivery_date >= order_date),
    -- Approval is recorded as a pair, and every status after approval requires it.
    CONSTRAINT ck_purchase_order_approval_pair CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_purchase_order_approved_states CHECK (
        status NOT IN ('APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED') OR approved_by IS NOT NULL),
    CONSTRAINT ck_purchase_order_cancellation CHECK ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL))
);
CREATE INDEX ix_purchase_order_supplier ON purchase_order (supplier_id);
CREATE INDEX ix_purchase_order_warehouse ON purchase_order (warehouse_id);
CREATE INDEX ix_purchase_order_status_date ON purchase_order (status, order_date);
CREATE INDEX ix_purchase_order_approved_by ON purchase_order (approved_by);

CREATE TABLE purchase_order_line (
    id                BIGINT         PRIMARY KEY,
    purchase_order_id BIGINT         NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    line_no           INTEGER        NOT NULL,
    product_id        BIGINT         NOT NULL REFERENCES product (id) ON DELETE RESTRICT,
    ordered_quantity  NUMERIC(19, 4) NOT NULL,
    received_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0,
    unit_price        NUMERIC(19, 4) NOT NULL,
    CONSTRAINT uq_purchase_order_line_no UNIQUE (purchase_order_id, line_no),
    -- One line per product: quantities for the same product are combined.
    CONSTRAINT uq_purchase_order_line_product UNIQUE (purchase_order_id, product_id),
    CONSTRAINT ck_purchase_order_line_no CHECK (line_no > 0),
    CONSTRAINT ck_purchase_order_line_ordered CHECK (ordered_quantity > 0),
    -- Over-receipt tolerance is 0% (architecture default); a tolerance would change this constraint.
    CONSTRAINT ck_purchase_order_line_received CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
    CONSTRAINT ck_purchase_order_line_price CHECK (unit_price >= 0)
);
CREATE INDEX ix_purchase_order_line_product ON purchase_order_line (product_id);
