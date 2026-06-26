# 17 — Production Hardening

## Goal

A system that handles failures gracefully, recovers automatically, and degrades safely rather than crashing entirely.

---

## Circuit Breakers

Use **Resilience4j** for outbound HTTP calls (payment gateway, shipping carrier, user service).

### Configuration
```yaml
resilience4j:
  circuitbreaker:
    instances:
      stripe:
        slidingWindowSize: 10
        failureRateThreshold: 50       # open after 50% failures
        waitDurationInOpenState: 30s   # wait 30s before trying again
        permittedNumberOfCallsInHalfOpenState: 3
      shipping-carrier:
        slidingWindowSize: 5
        failureRateThreshold: 60
        waitDurationInOpenState: 60s
```

### States
- **Closed**: requests flow normally
- **Open**: all requests fail fast (no waiting) — fallback activated
- **Half-Open**: limited requests allowed to test if service recovered

### Fallbacks
- Stripe circuit open → return `PaymentFailed` event immediately (customer can retry)
- Shipping carrier open → queue the shipment for retry when circuit closes
- User service open → use cached user data from Redis

---

## Retry

Resilience4j retry on `RestTemplate`/`WebClient` calls:
- Max 3 attempts
- Exponential backoff: 1s, 2s, 4s
- Retry on: `ConnectException`, `SocketTimeoutException`, HTTP 502, 503, 504
- Do NOT retry on: HTTP 400, 401, 403, 404, 409 (client errors — retrying won't help)

Kafka consumer retries: see doc 07.

---

## Timeouts

Set explicit timeouts on all outbound calls — never use the default (often infinite):

| Dependency | Connection Timeout | Read Timeout |
|---|---|---|
| PostgreSQL | 2s | 5s |
| Redis | 1s | 2s |
| Kafka producer | — | 30s (delivery timeout) |
| Stripe API | 5s | 10s |
| Shipping carrier API | 5s | 15s |
| Internal service calls | 1s | 3s |

Configurable via `application.yml` — never hardcoded.

---

## Bulkhead

Limit concurrent calls to slow dependencies so they can't exhaust the thread pool:

```yaml
resilience4j:
  bulkhead:
    instances:
      stripe:
        maxConcurrentCalls: 20
        maxWaitDuration: 100ms
```

If Stripe is slow and 20 threads are already waiting, the 21st call fails immediately instead of queuing indefinitely.

---

## Graceful Shutdown

On `SIGTERM` (pod being terminated):

1. Kubernetes stops routing new requests to the pod
2. Spring waits for in-flight HTTP requests to complete (max 30s)
3. Kafka consumers finish processing the current batch and commit offsets
4. DB connections closed cleanly

Configure in `application.yml`:
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

## Rate Limiting

Applied at the API Gateway level:

| Endpoint | Limit | Window |
|---|---|---|
| `POST /auth/login` | 5 requests | per IP per 15 min |
| `POST /orders` | 10 requests | per user per minute |
| `POST /files/upload-url` | 5 requests | per user per minute |
| All other endpoints | 100 requests | per user per minute |

Implemented with Redis atomic counters at the gateway.

Exceeded limits return `429 Too Many Requests` with `Retry-After` header.

---

## Optimistic Locking

The `orders` table has a `version` column. Every update increments it.

```
UPDATE orders
SET status = ?, version = version + 1, updated_at = now()
WHERE id = ? AND version = ?  ← expected version
```

If the row was modified by another thread since it was read, the WHERE clause matches 0 rows → `OptimisticLockException` → retry.

This prevents two concurrent requests from both successfully updating an order (e.g., two cancel requests arriving at the same millisecond).

---

## Idempotent API

For any mutation that could be retried (network error, client retry):

1. Client sends `Idempotency-Key: {uuid}` header
2. Server stores `(key, responseBody, statusCode)` in Redis with 24h TTL
3. On duplicate request: return cached response immediately without processing again

This prevents double-charges, double-orders, etc.

---

## Database Connection Pool Tuning

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # don't exceed DB's max_connections / num_pods
      minimum-idle: 2
      connection-timeout: 2000     # fail fast if no connection available
      idle-timeout: 600000
      max-lifetime: 1800000
      keepalive-time: 60000
```

Monitor `HikariCP` metrics to detect pool exhaustion early.

---

## Failover and Redundancy

| Component | Redundancy |
|---|---|
| Each microservice | 2+ pods (HPA min=2) |
| PostgreSQL | RDS Multi-AZ (automatic failover ~60s) |
| Redis | Redis Cluster (3 shards × 2 replicas) |
| Kafka | MSK with 3 brokers (ISR=2) |
| API Gateway | 3+ pods, load balanced |

Pod anti-affinity rules ensure no two pods of the same service land on the same Kubernetes node.

---

## Chaos Engineering (Optional Practice)

Intentionally inject failures to verify resilience:

- Kill a random pod → verify other pods serve traffic
- Kill Kafka broker → verify consumers reconnect and resume
- Insert 2s latency on Stripe calls → verify circuit breaker triggers
- Fill Redis memory → verify cache misses fall through to DB
- Simulate DB failover → verify reconnect within acceptable window

Use tools: Chaos Monkey for Spring Boot, LitmusChaos for Kubernetes.
