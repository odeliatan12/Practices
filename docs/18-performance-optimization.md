# 18 — Performance Optimization

## Performance Targets

| Metric | Target |
|---|---|
| POST /orders (create) | p95 < 200ms (response), saga completes < 5s |
| GET /orders/{id} | p95 < 50ms (cached), < 200ms (DB) |
| GET /orders (list) | p95 < 100ms |
| GET /orders/search | p95 < 300ms |
| WebSocket status update delivery | < 1s from DB write |
| Kafka event end-to-end latency | < 3s (consumer lag normal) |

---

## Database Optimization

### Indexing
Every column used in `WHERE`, `ORDER BY`, or `JOIN` should have an index.

Priority indexes:
```sql
-- Orders: most common queries
CREATE INDEX CONCURRENTLY idx_orders_customer_id ON orders(customer_id);
CREATE INDEX CONCURRENTLY idx_orders_status ON orders(status);
CREATE INDEX CONCURRENTLY idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX CONCURRENTLY idx_orders_customer_status ON orders(customer_id, status);

-- Outbox: poller query
CREATE INDEX CONCURRENTLY idx_outbox_pending ON outbox(created_at)
  WHERE published = false;

-- Stock reservations: cleanup job
CREATE INDEX CONCURRENTLY idx_reservations_expires ON stock_reservations(expires_at)
  WHERE status = 'PENDING';
```

Use `EXPLAIN ANALYZE` to verify index usage. `Seq Scan` on a large table = missing index.

### Query Optimization
- Fetch only needed columns — never `SELECT *` in application code
- Use pagination — never return unbounded result sets
- For bulk reads, use `IN (...)` with a reasonable limit (max 100 IDs)
- Avoid N+1 queries: use `JOIN FETCH` in JPA or batch loading

### Read Replicas
For heavy read endpoints (analytics, search fallback):
- Route read-only queries to a PostgreSQL read replica
- Spring DataSource routing: separate `@Transactional(readOnly = true)` beans point to replica

### Connection Pool
See doc 17. Keeping pool size lean reduces memory pressure and context switching.

---

## Caching

### Cache-First for Hot Data
- Order detail (5-minute TTL) — reduces DB reads by ~80% for active orders
- Stock level per SKU (60-second TTL) — stock display hits cache, actual reservation hits DB
- User profile (10-minute TTL) — auth check on every request is cached

### Cache Warming on Startup
Pre-populate caches after deployment to avoid thundering herd:
- Load top 1000 active orders into Redis during readiness check

### Avoid Cache Stampede
Use a lock (Redis `SET NX`) when repopulating a hot key to prevent multiple threads all hitting DB simultaneously when a cache entry expires.

---

## Kafka Performance

### Producer
```yaml
batch.size: 16384          # batch small messages together
linger.ms: 5               # wait up to 5ms to fill a batch
compression.type: snappy   # reduces network I/O significantly
```

### Consumer
```yaml
fetch.min.bytes: 1024      # wait for at least 1KB before returning batch
max.poll.records: 10       # keep low for order saga (latency over throughput)
```

### Partitioning
12 partitions on `orders` topic → up to 12 consumers in a group can process in parallel. Each partition processes one order at a time (preserves per-order ordering).

---

## API Response Time

### Async Where Possible
`POST /orders` returns `202 Accepted` immediately after persisting the order to DB and publishing the outbox event. The saga (inventory, payment) runs async — client uses WebSocket or polling to track progress.

### Compression
Enable gzip on the API Gateway for JSON responses:
- Responses > 1KB are compressed
- Reduces payload size by ~70% for large order lists

### HTTP/2
API Gateway and frontend served over HTTP/2 — multiplexing reduces connection overhead for many concurrent requests.

---

## Frontend Performance

### React Query Caching
- Order detail: `staleTime: 30000` (30 seconds) — serves cached response before refetching
- Stock levels: `staleTime: 5000` — slightly stale is OK for display

### Virtual Scrolling
Order lists with thousands of rows use virtual scrolling (only render visible rows).

### Code Splitting
Each page is a lazy-loaded chunk — initial bundle only includes the landing page and router. Other pages load on navigation.

### Image Optimization
Product images are served as WebP (smaller than JPEG at same quality) from CDN. The frontend requests the appropriate size (128px thumbnail in lists, 512px on detail page).

---

## JVM Tuning

### Memory
```
-Xms256m           # initial heap = final heap (avoids GC pauses from resizing)
-Xmx512m           # match the Kubernetes memory limit
-XX:+UseG1GC       # G1GC is default in JDK 11+, good for latency-sensitive apps
-XX:MaxGCPauseMillis=200
```

### Virtual Threads (Java 21)
Enable Spring Boot virtual threads for web and async tasks:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Virtual threads eliminate thread-per-request limitations — handle thousands of concurrent slow I/O requests with no memory penalty.

---

## Load Testing

Use **Gatling** or **k6** to validate performance before production:

Scenarios:
1. **Steady state**: 100 concurrent users placing orders for 5 minutes
2. **Spike**: ramp from 100 to 1000 users in 30 seconds
3. **Endurance**: 50 concurrent users for 30 minutes (check for memory leaks)
4. **Search**: 200 concurrent users performing order searches

Baseline: run against staging, compare p95 latency to targets in this doc. Fail the CI pipeline if p95 exceeds target by 20%.
