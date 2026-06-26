# 10 — Search System

## Why Elasticsearch

PostgreSQL can handle simple filtering (`WHERE status = 'SHIPPED'`), but struggles with:
- Full-text search across customer name, product name, order notes
- Complex multi-field faceted search (status + date range + product category)
- Sub-100ms response time on large order tables
- Aggregations for analytics (orders per hour, revenue by product)

Elasticsearch solves all of these without burdening the primary DB.

---

## What Gets Indexed

### Order Index (`orders`)

Each document represents one order, denormalized for fast reads:

```json
{
  "orderId": "uuid",
  "status": "SHIPPED",
  "customerId": "uuid",
  "customerName": "Jane Doe",
  "customerEmail": "jane@example.com",
  "totalAmount": 149.99,
  "currency": "USD",
  "lineItems": [
    {
      "sku": "WIDGET-XL-RED",
      "productName": "Red Widget XL",
      "quantity": 2,
      "unitPrice": 49.99
    }
  ],
  "shippingCity": "Austin",
  "shippingState": "TX",
  "trackingNumber": "1Z999AA10123456784",
  "notes": "Please leave at door",
  "createdAt": "2026-06-04T10:00:00Z",
  "updatedAt": "2026-06-04T14:30:00Z"
}
```

---

## Index Mapping

```json
{
  "mappings": {
    "properties": {
      "orderId":       { "type": "keyword" },
      "status":        { "type": "keyword" },
      "customerId":    { "type": "keyword" },
      "customerName":  { "type": "text", "analyzer": "standard" },
      "customerEmail": { "type": "keyword" },
      "totalAmount":   { "type": "double" },
      "currency":      { "type": "keyword" },
      "lineItems": {
        "type": "nested",
        "properties": {
          "sku":         { "type": "keyword" },
          "productName": { "type": "text", "analyzer": "standard" }
        }
      },
      "shippingCity":  { "type": "keyword" },
      "shippingState": { "type": "keyword" },
      "notes":         { "type": "text", "analyzer": "standard" },
      "createdAt":     { "type": "date" },
      "updatedAt":     { "type": "date" }
    }
  }
}
```

---

## Sync Strategy: Kafka → Elasticsearch

The order service does **not** write to Elasticsearch directly. A dedicated **search indexer** service consumes Kafka events and keeps the index in sync.

```
[Order Service]
  - Publishes OrderCreated / OrderStatusChanged
        │
        ▼
[Search Indexer] (separate Spring Boot service or component)
  - Listens to `orders` Kafka topic
  - On OrderCreated → upsert full document to ES
  - On OrderStatusChanged → partial update (status, updatedAt)
  - Idempotent: use orderId as ES document _id
```

This is **eventual consistency** — the index may lag a few seconds behind the DB. This is acceptable for search; exact current state comes from the Order Service API.

---

## Search API

The Order Service (or a dedicated search endpoint) exposes:

### Full-Text + Filter Search
```
GET /orders/search
  ?q=red+widget          (full-text across customerName, productName, notes)
  &status=SHIPPED
  &from=2026-01-01
  &to=2026-06-04
  &state=TX
  &page=0
  &size=20
  &sort=createdAt:desc
```

### Order Analytics Aggregations
```
GET /orders/analytics
  ?from=2026-06-01&to=2026-06-04

Response:
{
  "totalOrders": 1520,
  "totalRevenue": 228400.50,
  "byStatus": {
    "CONFIRMED": 45,
    "PROCESSING": 120,
    "SHIPPED": 800,
    "DELIVERED": 555
  },
  "byState": {
    "CA": 320,
    "TX": 215,
    ...
  }
}
```

---

## Query Building

Use Elasticsearch's **bool query** to combine filters:

```
bool:
  must:
    - multi_match on [customerName, productName, notes]  ← text search
  filter:
    - term: { status: "SHIPPED" }                        ← exact match
    - range: { createdAt: { gte: "2026-01-01" } }        ← date range
    - term: { shippingState: "TX" }                      ← facet
```

Filters don't affect relevance score and are cached by ES — much faster than `must` clauses.

---

## Index Management

### Aliases
Always write to and read from an **alias**, not the index directly:
- Write alias: `orders_write` → points to `orders_v1`
- Read alias: `orders_read` → points to `orders_v1`

On reindex (mapping change):
1. Create `orders_v2` with new mapping
2. Reindex from `orders_v1` into `orders_v2` using the Reindex API
3. Atomically swap `orders_read` alias from `v1` to `v2`
4. Delete `orders_v1` after validation

### Index Settings
```json
{
  "number_of_shards": 3,
  "number_of_replicas": 1,
  "refresh_interval": "1s"
}
```

---

## Performance Tips

- Use `filter` context over `query` context for non-text filters (no scoring needed)
- `keyword` fields for exact matches; `text` for full-text
- Paginate with `search_after` (cursor-based), not `from/size` beyond 10,000 results
- Profile slow queries with `"profile": true` in request body during development
