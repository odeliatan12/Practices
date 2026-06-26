# 08 — Redis Caching Strategy

## Why Redis

- **Performance**: Serve frequent reads from memory instead of hitting PostgreSQL
- **Session / token storage**: Fast lookup of refresh tokens and idempotency keys
- **Rate limiting**: Atomic counters per user/IP
- **Pub/Sub**: Optionally used to fan out WebSocket notifications within a service

---

## Cache Topology

Single Redis instance in dev. Redis Cluster (3 shards × 2 replicas) in production.

All keys are namespaced by service to avoid collisions:

```
order:{orderId}            → cached order DTO
inventory:sku:{sku}        → current stock level for a SKU
user:{userId}              → cached user profile
auth:refresh:{token}       → refresh token metadata
rate:login:{ip}            → login attempt counter
idempotency:{key}          → cached response for idempotent POST
processed:inv:{eventId}    → Kafka dedup marker (inventory consumer)
processed:pay:{eventId}    → Kafka dedup marker (payment consumer)
processed:notif:{eventId}  → Kafka dedup marker (notification consumer)
```

---

## What to Cache

### Order Details
- **Key**: `order:{orderId}`
- **Value**: Serialized `OrderDTO` (JSON)
- **TTL**: 5 minutes
- **Invalidation**: Write-through — update cache on every status change
- **Read strategy**: Cache-aside (check cache first, fallback to DB, populate cache)

### Stock Levels
- **Key**: `inventory:sku:{sku}`
- **Value**: `{ quantityAvailable, warehouseId, updatedAt }`
- **TTL**: 60 seconds (stale is acceptable for display; reservation goes to DB)
- **Invalidation**: Invalidated on any stock reservation or adjustment event

### User Profile
- **Key**: `user:{userId}`
- **Value**: Serialized `UserDTO`
- **TTL**: 10 minutes
- **Invalidation**: On profile update

### Orders List (per customer)
- Cache the first page only
- **Key**: `orders:customer:{customerId}:page:1`
- **TTL**: 2 minutes
- **Invalidation**: On any order status change for that customer

---

## What NOT to Cache

- Payment data (security/compliance — PCI DSS)
- Active stock reservations (must always be accurate)
- Auth decisions — always re-check roles from JWT

---

## Refresh Token Storage

Refresh tokens are stored in Redis AND the database:
- **Redis**: Fast lookup for token validation
- **DB**: Source of truth for rotation and revocation

```
Key: auth:refresh:{tokenHash}
Value: {
  userId: "...",
  issuedAt: "...",
  expiresAt: "...",
  userAgent: "...",
  ipAddress: "..."
}
TTL: 7 days (matches token lifetime)
```

On logout or rotation: `DEL auth:refresh:{tokenHash}`

---

## Idempotency Key Cache

For REST endpoints with `Idempotency-Key` header:

```
Key: idempotency:{method}:{key}
Value: { statusCode, responseBody }
TTL: 24 hours
```

- On first request: process normally, store result in Redis
- On duplicate request: return cached response immediately (same status code + body)

---

## Rate Limiting

Login endpoint rate limiting using Redis atomic increment:

```
Key: rate:login:{ip}
Value: attempt count (integer)
TTL: 15 minutes (sliding window on each increment)
```

Logic:
1. `INCR rate:login:{ip}`
2. If result = 1, set TTL: `EXPIRE rate:login:{ip} 900`
3. If result > 5, return 429 Too Many Requests

Same pattern applies to `POST /orders` per customer: max 10 orders per minute.

---

## Kafka Consumer Deduplication

```
Key: processed:{service}:{eventId}
Value: 1
TTL: 24 hours
```

Before processing a Kafka event, SETNX (set if not exists):
- If set: already processed → skip
- If not set: process the event

---

## Serialization

Use **Jackson JSON** for all cached values. Include `@class` discriminator for polymorphic types.

Keys are always strings. Values are always JSON strings (not binary).

---

## Cache Warming

On application startup, pre-warm:
- Top 100 most-ordered products' stock levels
- Admin dashboard aggregate counts (orders by status)

Use a `@EventListener(ApplicationReadyEvent.class)` to trigger warm-up.

---

## Cache Monitoring

Track via Redis INFO command and expose as metrics:
- `keyspace_hits` vs `keyspace_misses` → hit rate per key pattern
- `used_memory` → alert if approaching `maxmemory`
- `evicted_keys` → alert if non-zero (means memory pressure)
- `connected_clients` → watch for connection leak

Set `maxmemory-policy: allkeys-lru` so Redis evicts least-recently-used keys when full, rather than crashing.
