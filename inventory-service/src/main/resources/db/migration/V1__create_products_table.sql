CREATE TABLE products (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    sku                  VARCHAR(50)   NOT NULL UNIQUE,
    name                 VARCHAR(200)  NOT NULL,
    description          TEXT,
    price                NUMERIC(10,2) NOT NULL,
    low_stock_threshold  INTEGER       NOT NULL DEFAULT 10,
    active               BOOLEAN       NOT NULL DEFAULT true,
    version              INTEGER       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_sku    ON products(sku);
CREATE INDEX idx_products_active ON products(active);
