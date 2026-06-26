CREATE TABLE stock_reservations (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID        NOT NULL,
    product_id    UUID        NOT NULL REFERENCES products(id),
    warehouse_id  VARCHAR(50) NOT NULL,
    quantity      INTEGER     NOT NULL CHECK (quantity > 0),
    status        VARCHAR(20) NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservations_order_id   ON stock_reservations(order_id);
CREATE INDEX idx_reservations_product_id ON stock_reservations(product_id);
CREATE INDEX idx_reservations_expires_at ON stock_reservations(expires_at)
    WHERE status = 'PENDING';
