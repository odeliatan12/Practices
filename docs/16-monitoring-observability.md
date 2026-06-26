# 16 — Monitoring & Observability

## The Three Pillars

| Pillar | Tool | What it answers |
|---|---|---|
| **Metrics** | Prometheus + Grafana | Is the system healthy? How fast? |
| **Logs** | Loki + Grafana | What happened and when? |
| **Traces** | Micrometer Tracing + Tempo | Why is this request slow? |

---

## Metrics

### Instrumentation

Every Spring Boot service automatically exposes metrics via Micrometer + Prometheus at `/actuator/metrics` (Prometheus format at `/actuator/prometheus`).

**Default metrics (auto-collected):**
- HTTP request count, latency, error rate per endpoint
- JVM: heap/non-heap memory, GC pause time, thread count
- Connection pool: active, idle, pending, timeouts
- Kafka: consumer lag per topic/partition, producer send rate

**Custom business metrics (you add these):**

| Metric | Type | Labels | Description |
|---|---|---|---|
| `oms_orders_created_total` | Counter | `currency` | Orders placed |
| `oms_orders_status_change_total` | Counter | `from_status`, `to_status` | State transitions |
| `oms_order_processing_duration_seconds` | Histogram | — | Time PENDING → CONFIRMED |
| `oms_inventory_reservations_total` | Counter | `result` (success/failure) | Stock reservation attempts |
| `oms_payment_capture_total` | Counter | `result` (success/failure) | Payment capture attempts |
| `oms_kafka_outbox_lag_seconds` | Gauge | `service` | Age of oldest unpublished outbox row |
| `oms_notification_sent_total` | Counter | `channel`, `event_type` | Notifications dispatched |

### Grafana Dashboards

**Dashboard 1: System Overview**
- Request rate, error rate, p50/p95/p99 latency per service
- Kafka consumer lag per topic
- Redis hit rate
- Active WebSocket connections

**Dashboard 2: Business KPIs**
- Orders per minute (live)
- Order funnel: created → confirmed → shipped → delivered (conversion rates)
- Revenue per hour
- Payment failure rate

**Dashboard 3: Infrastructure**
- JVM heap usage per service
- DB connection pool utilization
- Pod CPU/memory vs limits

---

## Logging

### Structured Logging

All services log in **JSON format** using Logback + logstash-logback-encoder:

```json
{
  "timestamp": "2026-06-04T10:00:00.000Z",
  "level": "INFO",
  "service": "order-service",
  "traceId": "3d4f2a1b...",
  "spanId": "9e8f7c2a...",
  "orderId": "uuid",
  "customerId": "uuid",
  "message": "Order status changed from CONFIRMED to SHIPPED"
}
```

Always include these context fields in log statements:
- `orderId` (when processing an order)
- `customerId` (when processing a customer request)
- `eventId` (when processing a Kafka event)
- `traceId` / `spanId` (injected automatically by Micrometer Tracing)

### Log Levels

| Level | Use for |
|---|---|
| ERROR | Exceptions that require immediate attention; unexpected states |
| WARN | Expected failures that should be investigated (payment declined, stock low) |
| INFO | Key business events: order created, status changed, notification sent |
| DEBUG | Detailed flow for development debugging — not in production |

Set production log level to INFO. Enable DEBUG for specific packages via Actuator without restart.

### Log Shipping

Promtail (DaemonSet on each Kubernetes node) ships pod logs → Loki.

Query logs in Grafana with LogQL:
```
{service="order-service"} |= "PaymentFailed" | json | status="PAYMENT_FAILED"
```

---

## Distributed Tracing

### How it Works

Micrometer Tracing (Brave bridge) automatically:
- Generates a `traceId` for each incoming HTTP request
- Propagates the trace via `traceparent` header to downstream HTTP calls
- Propagates via Kafka headers to async consumers

All log statements include `traceId` — you can look up a specific order's entire journey across services by searching for the `traceId` that appeared in the order creation response.

### Viewing Traces

Traces are shipped to **Grafana Tempo**.

In Grafana, you can:
- Search by `traceId` to see the full request tree
- See latency breakdown: how long each service spent on a given order
- Find the service that caused a slow request

---

## Alerting

### Alert Rules (Prometheus/Alertmanager)

| Alert | Condition | Severity | Channel |
|---|---|---|---|
| `HighErrorRate` | HTTP 5xx rate > 1% for 5 min | P1 | PagerDuty |
| `HighLatency` | p95 > 2s for 5 min | P2 | Slack |
| `KafkaConsumerLag` | Lag > 1000 for 10 min | P2 | Slack |
| `PaymentFailureSpike` | Payment failure rate > 5% | P1 | PagerDuty |
| `OutboxStale` | Oldest unpublished outbox > 60s | P2 | Slack |
| `LowStock` | SKU below threshold | P3 | Slack #ops |
| `SLABreach` | Order stuck in PROCESSING > 48h | P2 | Slack #ops |
| `PodOOMKilled` | Pod OOMKilled in last 5 min | P1 | PagerDuty |
| `HighMemoryUsage` | JVM heap > 85% for 10 min | P2 | Slack |

### On-Call Runbooks

For each P1/P2 alert, a runbook exists at `docs/runbooks/` with:
- What the alert means
- Likely causes
- Diagnostic steps (which logs to check, which metrics to graph)
- Remediation steps

---

## Health Checks

Each service exposes:

```
/actuator/health/liveness
  → Returns UP if JVM is alive (not deadlocked)
  → Kubernetes restarts the pod if this fails

/actuator/health/readiness
  → Returns UP only if: DB connection OK + Kafka connection OK + Redis connection OK
  → Kubernetes stops routing traffic if this fails (pod is alive but not ready)
```

Custom health indicators for Kafka and Redis status are added alongside the default Spring Boot ones.
