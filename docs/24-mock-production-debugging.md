# 24 — Mock Production Debugging Session

## Overview

This document is a self-guided debugging exercise simulating a real production
performance incident. You play the role of the on-call engineer who has just
been paged. Work through each stage, form a hypothesis, then reveal the answer.

**Difficulty:** Beginner — one root cause, two visible symptoms, hints provided.
**Approach:** Logs and metrics analysis.

---

## The System Under Stress

```
Client
  ↓
API Gateway (port 8090)
  ├── CorrelationIdFilter
  ├── AuthFilter          ← validates JWT, checks Redis token cache
  ├── RateLimitFilter     ← increments Redis counter per user
  └── Router              ← forwards to order-service

Order Service (port 8081)
  ├── ShardResolvingFilter ← reads X-User-Id, sets ShardContext
  ├── OrderController
  ├── OrderService         ← checks L1 (Caffeine) → L2 (Redis) → L3 (DB)
  └── OrderCacheManager

Kafka
  ├── Producer: OrderService publishes OrderCreated, OrderStatusChanged
  └── Consumer: NotificationService, InventoryService consume events

Redis
  ├── Token cache (AuthFilter)
  ├── Rate limit counters (RateLimitFilter)
  └── Order cache L2 (OrderCacheManager)

PostgreSQL
  └── orders_db_shard0 (port 5433)
  └── orders_db_shard1 (port 5434)
```

---

## Stage 1 — The Alert Fires

You receive this alert at **14:32:07**:

```
[ALERT] P2 — High Latency Detected
─────────────────────────────────────────────────
Time:      14:32:07
Service:   order-service
Metric:    p99 response time = 3200ms  (threshold: 500ms)
Endpoint:  GET /api/v1/orders/customer/{customerId}
Error rate: 2%  (mostly timeouts)
Affected:  ~340 users reporting slow order page
─────────────────────────────────────────────────
```

### What You Know

- This endpoint calls `OrderService.getOrders(customerId)`
- That method checks Caffeine L1 → Redis L2 → PostgreSQL L3
- Normally p99 is around 45ms
- This started approximately 14:28:00 — 4 minutes before alert fired

### Your First Move

Before looking at anything — ask yourself:

```
Where in the chain is the slowdown?

Option A — Gateway layer    (AuthFilter, RateLimitFilter)
Option B — Cache layer      (Caffeine L1 or Redis L2)
Option C — Database layer   (PostgreSQL shard)
Option D — Kafka layer      (producer blocking order-service thread)
```

Think about it. Then read Stage 2.

---

## Stage 2 — The Logs

You pull logs from each layer. Read them carefully.

---

### Gateway Logs (api-gateway)

```
14:31:44 DEBUG CorrelationIdFilter  correlationId=a1b2 path=/api/v1/orders/customer/user-123
14:31:44 DEBUG AuthFilter           Authenticated request: userId=user-123 path=/api/v1/orders/customer/user-123
14:31:44 DEBUG RateLimitFilter      key=rl:user:user-123 count=45 limit=1000
14:31:44 INFO  Router               Forwarding to order-service http://localhost:8081

14:31:45 DEBUG CorrelationIdFilter  correlationId=c3d4 path=/api/v1/orders/customer/user-456
14:31:45 DEBUG AuthFilter           Authenticated request: userId=user-456
14:31:45 DEBUG RateLimitFilter      key=rl:user:user-456 count=12 limit=1000
14:31:45 INFO  Router               Forwarding to order-service http://localhost:8081
```

**Gateway observation:** Everything looks normal. Filters pass through quickly.
Latency is NOT in the gateway layer.

---

### Order Service Logs (order-service)

```
14:31:44 DEBUG ShardResolvingFilter  userId=user-123 shard=shard_0
14:31:44 DEBUG OrderCacheManager     L1 cache miss orderId=... customerId=user-123
14:31:44 DEBUG OrderCacheManager     L2 cache miss orderId=... customerId=user-123
14:31:44 DEBUG OrderCacheManager     L3 database hit orderId=...
14:31:47 DEBUG OrderCacheManager     L3 database hit orderId=...   ← 3 seconds later!
14:31:47 INFO  OrderController       getOrders customerId=user-123 duration=3241ms

14:31:45 DEBUG ShardResolvingFilter  userId=user-456 shard=shard_1
14:31:45 DEBUG OrderCacheManager     L1 cache hit orderId=...      ← instant
14:31:45 INFO  OrderController       getOrders customerId=user-456 duration=12ms

14:31:48 WARN  HikariPool            Connection is not available, request timed out after 2983ms
14:31:48 WARN  HikariPool            Connection is not available, request timed out after 2991ms
14:31:48 ERROR OrderController       Error fetching orders: Unable to acquire JDBC Connection
```

**Order service observation:** Two things stand out:
1. L1 and L2 cache are both missing for many users
2. HikariPool (database connection pool) is timing out

---

### Redis Metrics

```
Redis Memory Usage:
  14:20:00  used_memory: 512MB  / maxmemory: 512MB  ← at limit!
  14:25:00  used_memory: 512MB  / maxmemory: 512MB
  14:28:00  used_memory: 512MB  / maxmemory: 512MB

Redis Evictions:
  14:20:00  evicted_keys: 0
  14:25:00  evicted_keys: 12,400
  14:28:00  evicted_keys: 89,300   ← massive spike
  14:32:00  evicted_keys: 156,700

Redis Hit Rate:
  14:20:00  keyspace_hits: 98,200   keyspace_misses: 1,800   hit_rate: 98%
  14:25:00  keyspace_hits: 71,400   keyspace_misses: 28,600  hit_rate: 71%
  14:28:00  keyspace_hits: 34,100   keyspace_misses: 65,900  hit_rate: 34%
  14:32:00  keyspace_hits: 12,300   keyspace_misses: 87,700  hit_rate: 12%  ← collapsed
```

**Redis observation:** Redis hit rate collapsed from 98% to 12% between 14:20 and
14:32. Evictions spiked massively starting at 14:25.

---

### Database Metrics (PostgreSQL shard_0)

```
Active connections:
  14:20:00  active: 3   idle: 7   max_pool: 10
  14:25:00  active: 8   idle: 2   max_pool: 10
  14:28:00  active: 10  idle: 0   max_pool: 10  ← pool exhausted
  14:31:00  active: 10  idle: 0   max_pool: 10  ← threads waiting for connection
  14:32:00  active: 10  idle: 0   max_pool: 10

Query latency (p99):
  14:20:00  findByCustomerId: 8ms
  14:25:00  findByCustomerId: 45ms
  14:28:00  findByCustomerId: 890ms
  14:32:00  findByCustomerId: 2800ms  ← under extreme load

Slow query log:
  14:28:03  duration=1240ms  SELECT * FROM orders WHERE customer_id = ?
  14:28:07  duration=1890ms  SELECT * FROM orders WHERE customer_id = ?
  14:29:11  duration=2340ms  SELECT * FROM orders WHERE customer_id = ?
```

**Database observation:** Connection pool on shard_0 is fully exhausted. Every
new request queues waiting for a connection — this is where the 3 second latency
comes from. The database itself is slow because it is handling far more queries
than normal.

---

### Kafka Metrics

```
Topic: order-created
  Producer send rate:   14:20=120/s   14:28=118/s   14:32=115/s  ← stable
  Producer latency p99: 14:20=12ms    14:28=45ms    14:32=280ms  ← rising

Consumer group: inventory-service
  Lag partition-0:  14:20=2    14:28=340   14:32=1240  ← lag growing
  Lag partition-1:  14:20=1    14:28=290   14:32=1180  ← lag growing

Consumer group: notification-service
  Lag partition-0:  14:20=0    14:28=450   14:32=2300  ← severely behind

Producer errors:
  14:31:22 WARN  KafkaProducerConfig  Request exceeded timeout 30000ms waiting for acks
  14:31:45 WARN  KafkaProducerConfig  Request exceeded timeout 30000ms waiting for acks
  14:31:58 ERROR KafkaProducerConfig  Failed to send message to topic order-created
                                      after 3 retries
```

**Kafka observation:** Consumer lag is growing on both consumer groups.
Inventory-service and notification-service are falling behind processing events.
Producer is starting to time out.

---

### Hint — Connecting the Dots

```
Something happened around 14:20 that caused:
    Redis memory to hit its limit
        ↓
    Redis started evicting cached orders (89,000+ evictions)
        ↓
    Cache hit rate collapsed from 98% to 12%
        ↓
    88% of requests now hit PostgreSQL instead of cache
        ↓
    Database connection pool exhausted (10/10 connections used)
        ↓
    New requests queue waiting for a connection (3 second wait)
        ↓
    Kafka producer threads also waiting → producer timeouts
        ↓
    Consumer lag builds up because new orders not published fast enough
```

What caused Redis memory to hit its limit at 14:20?

---

## Stage 3 — Root Cause and Fix

### The Root Cause

```
A batch job ran at 14:18 that loaded 200,000 product catalogue entries
into Redis as part of an eager cache warming exercise.

Each entry averaged 2.5KB → 200,000 × 2.5KB = 500MB

Redis maxmemory was set to 512MB.

The batch job consumed 97% of Redis memory in 2 minutes,
leaving almost no room for order cache entries.

Redis eviction policy (allkeys-lru) then aggressively evicted
order cache entries to make room — destroying the cache.
```

---

### Chain of Failures

```
14:18:00 — Batch job starts loading product catalogue into Redis
14:20:00 — Redis memory hits 512MB limit
14:20:00 — Redis begins evicting order cache entries (LRU)
14:25:00 — 12,400 order cache entries evicted — hit rate drops to 71%
14:28:00 — 89,300 order cache entries evicted — hit rate drops to 34%
14:28:00 — Database connection pool starts filling up
14:31:00 — Connection pool fully exhausted (10/10)
14:31:22 — Kafka producer timeouts begin (threads stuck waiting for DB)
14:32:00 — Alert fires — p99 latency = 3200ms
14:32:07 — You are paged
```

---

### What Happened to Kafka

Kafka was impacted as a **secondary effect**, not the root cause:

**Producer side:**
```
OrderService.createOrder() is called
    ↓
Saves to database — but DB connection pool is exhausted
    ↓
Thread waits 2-3 seconds for a DB connection
    ↓
Finally saves to DB
    ↓
Tries to publish to Kafka — but thread has been waiting so long
    ↓
Kafka producer timeout fires (30 seconds) before ack received
    ↓
Producer retries 3 times — eventually fails
    ↓
OrderCreated event never published to Kafka
```

**Consumer side:**
```
InventoryService consuming OrderCreated events
    ↓
Each event triggers an inventory DB lookup
    ↓
InventoryService DB also under pressure (similar cache eviction problem)
    ↓
Each event takes longer to process
    ↓
Consumer falls behind — lag builds from 2 to 1240
    ↓
New orders not having stock deducted in time
    ↓
Risk of overselling
```

**NotificationService consuming OrderStatusChanged:**
```
Lag grows from 0 to 2300
    ↓
Users not receiving order confirmation emails
    ↓
Customer support tickets start arriving
```

---

### The Fix — Immediate (Stop the Bleeding)

```bash
# Step 1 — Flush the product catalogue keys immediately
redis-cli -p 6380 --scan --pattern "product:*" | xargs redis-cli -p 6380 DEL

# Step 2 — Verify memory freed
redis-cli -p 6380 info memory | grep used_memory_human

# Step 3 — Watch hit rate recover
redis-cli -p 6380 info stats | grep keyspace
```

Once Redis memory is freed:
```
Order cache entries reload from DB naturally as requests come in
    ↓
Hit rate recovers over 5-10 minutes
    ↓
DB connection pool pressure drops
    ↓
Kafka producer timeouts stop
    ↓
Consumer lag drains as processing speed recovers
```

---

### The Fix — Long Term (Prevent Recurrence)

**Fix 1 — Separate Redis instances per concern**

```yaml
# application.yml

# Order cache — dedicated Redis instance
spring.data.redis.order-cache:
  host: localhost
  port: 6380
  maxmemory: 4gb

# Product catalogue — separate Redis instance
spring.data.redis.product-cache:
  host: localhost
  port: 6381
  maxmemory: 8gb

# Rate limiting and tokens — separate Redis instance
spring.data.redis.session:
  host: localhost
  port: 6382
  maxmemory: 1gb
```

Product catalogue can no longer evict order cache — completely isolated.

---

**Fix 2 — Set TTL on all cache entries**

```java
// OrderCacheManager — already has TTL
redisTemplate.opsForValue().set(redisKey, order, Duration.ofMinutes(10));

// Product cache — must also have TTL
redisTemplate.opsForValue().set(productKey, product, Duration.ofHours(1));
// Without TTL — keys never expire, memory fills permanently
```

---

**Fix 3 — Monitor Redis memory before batch jobs**

```java
@Component
public class RedisSafeWriter {

    private static final double MAX_MEMORY_THRESHOLD = 0.70; // 70%

    private final RedisTemplate<String, String> redisTemplate;

    public boolean isSafeToWrite() {
        // check memory usage before large batch writes
        Properties info = redisTemplate.getConnectionFactory()
                .getConnection().serverCommands().info("memory");

        long usedMemory = Long.parseLong(info.getProperty("used_memory"));
        long maxMemory  = Long.parseLong(info.getProperty("maxmemory"));

        double usageRatio = (double) usedMemory / maxMemory;

        if (usageRatio > MAX_MEMORY_THRESHOLD) {
            // memory above 70% — do not proceed with batch write
            log.warn("Redis memory at {}% — skipping batch cache warm",
                    (int)(usageRatio * 100));
            return false;
        }
        return true;
    }
}
```

---

**Fix 4 — Increase database connection pool**

```yaml
# application.yml — order-service
spring:
  datasource:
    shard0:
      hikari:
        maximum-pool-size: 20  # was 10 — doubled
        minimum-idle: 5
        connection-timeout: 2000
        idle-timeout: 600000
```

Gives headroom when cache miss rate spikes.

---

**Fix 5 — Kafka producer — add retries and backoff**

```yaml
spring:
  kafka:
    producer:
      retries: 5                # was 3
      properties:
        retry.backoff.ms: 1000  # wait 1s between retries
        request.timeout.ms: 60000  # give more time before timeout
        delivery.timeout.ms: 120000
```

---

### Monitoring — What to Watch After Fix

```
Redis hit rate        → should recover to 95%+ within 10 minutes
Redis eviction count  → should drop to 0
DB active connections → should drop below 5
Kafka consumer lag    → should drain to 0 within 15 minutes
p99 latency           → should return to sub 50ms
```

---

### Key Lessons

| Lesson | What to do |
|---|---|
| Never share Redis between unrelated data | Separate instances per concern |
| Always set TTL on cache entries | Nothing lives forever in Redis |
| Monitor Redis memory before batch jobs | Check threshold before writing |
| DB connection pool must handle cache miss storms | Size pool for worst case |
| Kafka lag is a symptom — not just a Kafka problem | Trace back to the real cause |
| One failure cascades to everything downstream | Understand your dependency chain |

---

### Interview Answer — Root Cause in One Paragraph

> At 14:18, a batch job loaded 500MB of product catalogue data into a Redis
> instance with a 512MB memory limit. This triggered aggressive LRU eviction of
> order cache entries, collapsing the hit rate from 98% to 12%. The resulting
> cache miss storm sent 88% of order read requests directly to PostgreSQL,
> exhausting the 10-connection HikariCP pool on shard_0. Threads queuing for
> database connections caused Kafka producer timeouts, which in turn caused
> consumer lag to build on inventory-service and notification-service. The fix
> was to flush the product catalogue keys immediately, separate Redis instances
> by concern, and increase the database connection pool size.
