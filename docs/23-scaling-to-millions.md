# 23 — Scaling to Millions

## Current Architecture Limits

The baseline architecture (Phases 1–8) handles roughly:
- ~500 orders/minute with the default setup
- ~10,000 concurrent WebSocket connections
- ~1TB of order data before PostgreSQL needs attention

This document describes the changes needed to reach the next order of magnitude.

---

## Database Scaling

### Read Scaling: Read Replicas

At high read volume, add PostgreSQL read replicas:
- Route all `SELECT` queries to replica(s) using Spring's `AbstractRoutingDataSource`
- Write queries always go to primary
- Replicas lag 10–100ms behind primary — acceptable for dashboards and search, not for read-your-own-writes

### Write Scaling: Partitioning

When the `orders` table exceeds ~50M rows, queries slow down even with indexes.

**Range partitioning by `created_at`** (monthly partitions):
```sql
CREATE TABLE orders_2026_06 PARTITION OF orders
  FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
```

Benefits:
- Queries with date filters only scan relevant partition
- Old partitions can be archived to cheaper storage
- Index size stays manageable per partition

### Write Scaling: Horizontal Sharding (Advanced)

When a single primary can't handle write volume:
- Shard by `customer_id` range or hash
- Each shard is a separate PostgreSQL instance
- Routing layer: consistent hashing maps `customerId → shard`

Tradeoff: cross-shard queries (e.g., analytics joining orders across all customers) become very expensive. Use Elasticsearch for those instead.

---

## Kafka Scaling

### More Partitions

Increase partitions on high-volume topics:
- `orders`: 12 → 50 partitions (each partition is processed by one consumer thread)
- Consumer group size must be ≤ partition count

### Separate Clusters

Split into multiple Kafka clusters as topics grow:
- `orders-cluster`: high-priority order saga events
- `analytics-cluster`: slower, eventually-consistent analytics events
- Cross-cluster replication via MirrorMaker 2

### Schema Registry

At scale, introduce a **Schema Registry** (Confluent or AWS Glue):
- Kafka messages use Avro instead of JSON (smaller, faster, typed)
- Schema registry enforces compatibility between versions
- Producers and consumers share schema by ID (not by embedding full schema)

---

## Caching at Scale

### Redis Cluster

Move from single Redis instance to Redis Cluster:
- 3 shards × 2 replicas = 6 nodes
- Data automatically sharded across nodes by key hash slot
- Survives shard failure (replica promoted automatically)

### Multi-Level Cache

For extremely hot data (product catalog, top SKU prices):
- L1: **In-process Caffeine cache** (nanosecond access, per-pod, max 1000 entries)
- L2: **Redis** (microsecond access, shared across pods)
- L3: **PostgreSQL** (millisecond access)

Updates invalidate L2 (Redis) and let L1 expire by TTL (eventually consistent per pod).

---

## Application Scaling

### Horizontal Scaling (Kubernetes HPA)

Scale pods based on CPU and custom metrics:
```yaml
metrics:
  - type: External
    external:
      metric:
        name: kafka_consumer_lag_sum
        selector:
          matchLabels:
            topic: orders
      target:
        type: AverageValue
        averageValue: 100    # scale up if lag > 100 messages per pod
```

### KEDA (Kubernetes Event-Driven Autoscaling)

Scale consumers directly based on Kafka lag:
- 0 replicas when lag = 0 (save cost)
- Scale to 1 replica per N messages of lag
- Max replicas = partition count

### Stateless Services

All services must be stateless — any pod can handle any request. This means:
- No in-memory session state (use Redis)
- No local file storage (use S3/MinIO)
- No sticky sessions (load balance freely)

---

## WebSocket Scaling

With multiple API gateway instances, WebSocket connections are distributed across pods.

### Message Broker Relay

Use **Redis Pub/Sub** or **RabbitMQ** as a relay:
1. Service A processes an order event and needs to push to user's WebSocket
2. Service A doesn't know which pod holds that user's connection
3. Service A publishes to Redis channel: `ws:user:{userId}`
4. All gateway pods subscribe to Redis
5. The pod holding the user's connection forwards the STOMP message

Spring's STOMP broker relay (`StompBrokerRelayMessageHandler` with RabbitMQ) handles this transparently.

---

## CDN and Edge

### Static Assets
- Frontend JS/CSS: distributed via CloudFront, cached at edge globally
- Product images: served from CloudFront, origin is S3
- Cache-Control: immutable for versioned assets

### API at Edge (Advanced)

For global users with latency requirements:
- Deploy API Gateway to multiple AWS regions
- Route via AWS Global Accelerator to nearest region
- Kafka cross-region replication for eventual consistency across regions

---

## Observability at Scale

With many services and pods, observability becomes critical:

### Metrics Cardinality

Keep Prometheus label cardinality low — don't use `orderId` or `customerId` as metric labels (creates millions of time series). Use aggregate labels: `status`, `service`, `region`.

### Sampling

At high throughput, trace 100% of requests is expensive:
- Sample 10% of traces in production (still millions of traces/day)
- Always sample errored requests (100%)
- Always sample slow requests (p99+)

Use OpenTelemetry with probabilistic sampling.

### Log Volume

At millions of orders/day, log volume becomes expensive:
- Set production log level to `WARN` (not INFO)
- Only log errors and key business events at INFO+
- Use structured logging — query with LogQL in Grafana rather than grep

---

## Cost Optimization

| Area | Optimization |
|---|---|
| Compute | Use Spot/Preemptible instances for non-critical services |
| Storage | Archive orders older than 2 years to S3 Glacier |
| Kafka | Tiered storage — move old segments to S3 automatically |
| DB | Reserved RDS instances (commit to 1 year for 40% discount) |
| Images | Delete old Docker images from registry via lifecycle policy |
| Monitoring | Downsample old Prometheus metrics (1m resolution → 5m after 30 days) |

---

## Milestones for Scale

| Load | Required Changes |
|---|---|
| 100 orders/min | Baseline architecture (Phases 1–8) |
| 1,000 orders/min | Read replicas, Redis Cluster, HPA tuning |
| 10,000 orders/min | Kafka partition increase, KEDA, DB partitioning |
| 100,000 orders/min | DB sharding, multi-region, schema registry, edge caching |
