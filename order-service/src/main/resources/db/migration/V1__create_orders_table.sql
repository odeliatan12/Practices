CREATE TABLE orders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID         NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    total_amount    NUMERIC(12,2) NOT NULL,
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    shipping_name   VARCHAR(100),
    shipping_line1  VARCHAR(200),
    shipping_city   VARCHAR(100),
    shipping_state  VARCHAR(50),
    shipping_zip    VARCHAR(20),
    shipping_country CHAR(2),
    notes           TEXT,
    version         INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status      ON orders(status);
CREATE INDEX idx_orders_created_at  ON orders(created_at DESC);
