# 07 — Kafka Event-Driven Design

## Why Kafka

- **Decoupling**: Order Service doesn't call Inventory Service directly — it publishes an event and anyone who cares can react
- **Durability**: Events are persisted on disk — if the Notification Service is down, it catches up when it restarts
- **Replay**: Can reprocess events from a past offset for debugging or rebuilding read models
- **Fan-out**: One event consumed by multiple independent services

---

## Topic Design

| Topic | Partitions | Retention | Description |
|---|---|---|---|
| `orders` | 12 | 7 days | Order lifecycle events |
| `inventory` | 6 | 7 days | Stock reservation events |
| `payments` | 6 | 30 days | Payment events (audit) |
| `fulfillment` | 6 | 7 days | Shipment and delivery events |
| `notifications` | 3 | 3 days | Outbound notification jobs |
| `orders.dlq` | 3 | 30 days | Dead-letter queue for order events |
| `inventory.dlq` | 3 | 30 days | Dead-letter queue for inventory events |

### Partition Key Strategy
- Use `orderId` as the partition key for all order-related events
- Guarantees all events for the same order land in the same partition → preserved ordering per order

---

## Event Catalog

### Order Service → publishes

| Event | Trigger | Key Fields in Payload |
|---|---|---|
| `OrderCreated` | New order placed | orderId, customerId, lineItems[], totalAmount |
| `OrderStatusChanged` | Any status transition | orderId, oldStatus, newStatus |
| `OrderCancelled` | Customer or system cancels | orderId, reason |
| `ReturnRequested` | Customer requests return | orderId, returnItems[], reason |

### Inventory Service → publishes

| Event | Trigger | Key Fields |
|---|---|---|
| `StockReserved` | All line items reserved | orderId, reservations[] |
| `StockUnavailable` | One or more SKUs have no stock | orderId, unavailableSkus[] |
| `StockReleased` | Reservation cancelled | orderId |
| `LowStockAlert` | SKU quantity drops below threshold | sku, warehouseId, quantity |

### Payment Service → publishes

| Event | Trigger | Key Fields |
|---|---|---|
| `PaymentCaptured` | Stripe charge successful | orderId, paymentId, amount |
| `PaymentFailed` | Stripe charge failed | orderId, reason, retryable |
| `RefundInitiated` | Refund process started | orderId, refundId, amount |
| `RefundCompleted` | Stripe refund confirmed | orderId, refundId |

### Fulfillment Service → publishes

| Event | Trigger | Key Fields |
|---|---|---|
| `FulfillmentJobCreated` | Job queued in WMS | orderId, jobId |
| `OrderShipped` | Package dispatched | orderId, trackingNumber, carrier |
| `OrderDelivered` | Carrier confirms delivery | orderId, deliveredAt |
| `ReturnReceived` | Return item arrives at warehouse | orderId, returnId |

---

## Consumer Groups

Each service has its own consumer group, so each service gets its own copy of every event.

| Consumer Group | Topics Consumed |
|---|---|
| `inventory-service` | `orders` |
| `payment-service` | `inventory` |
| `fulfillment-service` | `payments`, `orders` |
| `order-service` | `inventory`, `payments`, `fulfillment` |
| `notification-service` | `orders`, `inventory`, `payments`, `fulfillment` |
| `search-indexer` | `orders` |

---

## Message Format

All events use **JSON** serialization with the following envelope:

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "OrderCreated",
  "aggregateType": "Order",
  "aggregateId": "order-uuid",
  "source": "order-service",
  "occurredAt": "2026-06-04T10:00:00.000Z",
  "version": 1,
  "correlationId": "request-trace-id",
  "payload": {
    "customerId": "...",
    "lineItems": [...],
    "totalAmount": 149.99
  }
}
```

Schema versioning: increment `version` when adding required fields. Consumers must handle unknown versions gracefully (skip or route to DLQ).

---

## Transactional Outbox Pattern

The key challenge: "publish to Kafka AND update DB atomically."

Without care, you can update the DB but fail to publish the Kafka event (or vice versa).

### Solution: Outbox Table

1. Within the same DB transaction that changes order status, write a row to `outbox` table
2. A background **outbox poller** (Spring `@Scheduled`) reads unpublished rows every 100ms
3. Publishes each event to Kafka
4. Marks the row as `published = true`

This guarantees **at-least-once delivery** — the event may be published more than once (if the poller crashes after publish but before marking done), so consumers must be idempotent.

---

## Consumer Idempotency

Each consumer stores processed `eventId` values in Redis:

```
Key:   processed:inventory:{eventId}
Value: 1
TTL:   24 hours
```

On receiving an event:
1. Check Redis for `eventId`
2. If present → skip (already processed)
3. If absent → process → store in Redis

---

## Error Handling and Retries

### Retry Policy
- On processing failure: retry 3 times with exponential backoff (1s, 2s, 4s)
- Use Spring Kafka's `SeekToCurrentErrorHandler` with backoff

### Dead Letter Queue
- After max retries, publish to `{topic}.dlq`
- DLQ messages include: original message + error type + stack trace + attempt count
- Ops team monitors DLQ size via alerts
- Manual replay tool: read from DLQ → republish to original topic

---

## Kafka Configuration

### Producer Settings
```yaml
acks: all                  # wait for all in-sync replicas
retries: 3
enable.idempotence: true   # exactly-once producer semantics
compression.type: snappy
```

### Consumer Settings
```yaml
auto.offset.reset: earliest
enable.auto.commit: false   # manual commit after successful processing
isolation.level: read_committed
max.poll.records: 10        # small batch for order saga to keep latency low
```

---

## Schema Evolution Rules

1. Always add new fields as **optional** — never remove or rename required fields
2. Bump `version` field when adding a new required field
3. Consumers must skip events for versions they don't understand
4. Keep old event types alive for at least one deployment cycle before removing consumers
