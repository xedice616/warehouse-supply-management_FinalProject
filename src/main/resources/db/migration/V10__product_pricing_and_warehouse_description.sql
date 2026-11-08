ALTER TABLE product
    ADD COLUMN min_stock_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN purchase_price     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN selling_price      NUMERIC(19, 4),
    ADD CONSTRAINT ck_product_min_stock_quantity CHECK (min_stock_quantity >= 0),
    ADD CONSTRAINT ck_product_purchase_price CHECK (purchase_price >= 0),
    ADD CONSTRAINT ck_product_selling_price CHECK (selling_price IS NULL OR selling_price >= 0);

-- Defaults only backfill existing rows; new rows must state their values.
ALTER TABLE product
    ALTER COLUMN min_stock_quantity DROP DEFAULT,
    ALTER COLUMN purchase_price DROP DEFAULT;

CREATE INDEX ix_product_replenishment ON product (id) WHERE active AND min_stock_quantity > 0;

ALTER TABLE warehouse
    ADD COLUMN description VARCHAR(1000);

CREATE INDEX ix_warehouse_name_prefix ON warehouse (lower(name) text_pattern_ops);
CREATE INDEX ix_product_category_name_prefix ON product_category (lower(name) text_pattern_ops);
