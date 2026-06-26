# 04 — Database Design

## Database Per Service

Each microservice owns its own PostgreSQL schema. No service reads from another service's tables. Cross-service data needs go through the API.

| Service | Schema | Key Tables |
|---|---|---|
| Order Service | `orders` | `orders`, `line_items`, `order_events`, `outbox` |
| Inventory Service | `inventory` | `products`, `stock_levels`, `stock_reservations` |
| Payment Service | `payments` | `payments`, `refunds` |
| Fulfillment Service | `fulfillment` | `fulfillment_jobs`, `shipments`, `tracking_events` |
| User Service | `users` | `users`, `roles`, `refresh_tokens` |

---

## Order Service Schema

### `orders`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
customer_id     UUID         NOT NULL
status          VARCHAR(30)  NOT NULL
total_amount    NUMERIC(12,2) NOT NULL
currency        CHAR(3)      NOT NULL DEFAULT 'USD'
shipping_name   VARCHAR(100)
shipping_line1  VARCHAR(200)
shipping_city   VARCHAR(100)
shipping_state  VARCHAR(50)
shipping_zip    VARCHAR(20)
shipping_country CHAR(2)
notes           TEXT
version         INTEGER      NOT NULL DEFAULT 0   -- optimistic locking
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

### `line_items`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
order_id        UUID         NOT NULL REFERENCES orders(id)
sku             VARCHAR(50)  NOT NULL
product_name    VARCHAR(200) NOT NULL
quantity        INTEGER      NOT NULL CHECK (quantity > 0)
unit_price      NUMERIC(10,2) NOT NULL
subtotal        NUMERIC(12,2) GENERATED ALWAYS AS (quantity * unit_price) STORED
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

### `order_events` (audit log / event sourcing lite)
```
id              BIGSERIAL    PRIMARY KEY
order_id        UUID         NOT NULL REFERENCES orders(id)
event_type      VARCHAR(50)  NOT NULL   -- e.g. ORDER_CREATED, STATUS_CHANGED
old_status      VARCHAR(30)
new_status      VARCHAR(30)
actor           VARCHAR(100)            -- user ID or system name
metadata        JSONB
occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
```

### `outbox` (Transactional Outbox Pattern)
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
aggregate_type  VARCHAR(50)  NOT NULL   -- e.g. 'Order'
aggregate_id    UUID         NOT NULL
event_type      VARCHAR(100) NOT NULL
payload         JSONB        NOT NULL
published       BOOLEAN      NOT NULL DEFAULT false
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
published_at    TIMESTAMPTZ
```

---

## Inventory Service Schema

### `products`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
sku             VARCHAR(50)  NOT NULL UNIQUE
name            VARCHAR(200) NOT NULL
description     TEXT
unit_cost       NUMERIC(10,2)
low_stock_threshold INTEGER  NOT NULL DEFAULT 10
active          BOOLEAN      NOT NULL DEFAULT true
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

### `stock_levels`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
product_id      UUID         NOT NULL REFERENCES products(id)
warehouse_id    VARCHAR(50)  NOT NULL
quantity_on_hand INTEGER     NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0)
quantity_reserved INTEGER    NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0)
quantity_available INTEGER   GENERATED ALWAYS AS (quantity_on_hand - quantity_reserved) STORED
updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
UNIQUE (product_id, warehouse_id)
```

### `stock_reservations`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
order_id        UUID         NOT NULL
product_id      UUID         NOT NULL REFERENCES products(id)
warehouse_id    VARCHAR(50)  NOT NULL
quantity        INTEGER      NOT NULL
status          VARCHAR(20)  NOT NULL   -- PENDING, CONFIRMED, RELEASED
expires_at      TIMESTAMPTZ  NOT NULL   -- reservation expires if order not confirmed
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

---

## Payment Service Schema

### `payments`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
order_id        UUID         NOT NULL UNIQUE
amount          NUMERIC(12,2) NOT NULL
currency        CHAR(3)      NOT NULL
status          VARCHAR(20)  NOT NULL   -- PENDING, CAPTURED, FAILED, REFUNDED
provider        VARCHAR(30)  NOT NULL   -- e.g. 'stripe'
provider_ref    VARCHAR(100)            -- Stripe payment intent ID
failure_reason  TEXT
idempotency_key VARCHAR(100) UNIQUE     -- prevents duplicate charges
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

### `refunds`
```
id              UUID         PRIMARY KEY DEFAULT gen_random_uuid()
payment_id      UUID         NOT NULL REFERENCES payments(id)
amount          NUMERIC(12,2) NOT NULL
reason          TEXT
status          VARCHAR(20)  NOT NULL
provider_ref    VARCHAR(100)
created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
```

---

## Indexing Strategy

### Orders
- `idx_orders_customer_id` — list orders by customer
- `idx_orders_status` — filter by status for ops dashboard
- `idx_orders_created_at` — time-range queries
- `idx_outbox_published_created` on `(published, created_at)` — outbox poller

### Inventory
- `idx_stock_levels_product_warehouse` — UNIQUE index (already PK-covered)
- `idx_reservations_order_id` — look up reservations by order
- `idx_reservations_expires_at` — cleanup job for expired reservations

---

## Migrations

Use **Flyway** for schema versioning.

Files live in `src/main/resources/db/migration/` and follow naming:
```
V1__create_orders_table.sql
V2__create_line_items_table.sql
V3__create_outbox_table.sql
V4__add_index_orders_customer_id.sql
```

Rules:
- Never edit a migration that has already been applied
- New changes always get a new migration file
- Rollback migrations are optional but write them for destructive changes

---

## Data Retention

| Table | Retention | Strategy |
|---|---|---|
| `orders` | Indefinite (legal) | Archive to cold storage after 2 years |
| `order_events` | 1 year | Partition by month, drop old partitions |
| `outbox` | 7 days after published | Scheduled cleanup job |
| `stock_reservations` | 90 days after closed | Scheduled cleanup job |
