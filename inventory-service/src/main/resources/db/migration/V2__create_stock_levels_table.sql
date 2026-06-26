CREATE TABLE stock_levels (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID        NOT NULL REFERENCES products(id),
    warehouse_id        VARCHAR(50) NOT NULL,
    quantity_on_hand    INTEGER     NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
    quantity_reserved   INTEGER     NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_product_warehouse UNIQUE (product_id, warehouse_id)
);

CREATE INDEX idx_stock_levels_product_id ON stock_levels(product_id);
