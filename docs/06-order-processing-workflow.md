# 06 — Order Processing Workflow

## Overview

Order processing is the core business flow. It coordinates five services — Order, Inventory, Payment, Fulfillment, and Notification — using a **choreography-based saga** over Kafka events.

---

## Full Saga: Happy Path

```
Customer POSTs /orders
        │
        ▼
[Order Service]
  - Validates request
  - Creates order in DB (status: PENDING)
  - Publishes OrderCreated event
        │
        ▼ (Kafka: orders topic)
        │
  ┌─────┴──────────────────────────────────┐
  │                                        │
  ▼                                        ▼
[Inventory Service]               [Notification Service]
  - Listens OrderCreated            - Sends "We received your order" email
  - Checks stock per SKU
  - Creates reservations
  - Publishes StockReserved
        │
        ▼ (Kafka: inventory topic)
[Payment Service]
  - Listens StockReserved
  - Calls Stripe to capture payment
  - Publishes PaymentCaptured
        │
        ▼ (Kafka: payments topic)
        │
  ┌─────┴─────────────────────────────────┐
  │                                       │
  ▼                                       ▼
[Order Service]                  [Notification Service]
  - Updates status → CONFIRMED     - Sends "Order confirmed" email + SMS
        │
        ▼
[Fulfillment Service]
  - Listens OrderConfirmed
  - Creates fulfillment job
  - Simulates warehouse pick/pack
  - Publishes OrderShipped
        │
        ▼
  ┌─────┴─────────────────────────────────┐
  │                                       │
  ▼                                       ▼
[Order Service]                  [Notification Service]
  - Updates status → SHIPPED       - Sends "Your order is on the way" email
        │
  [Carrier delivers]
        │
        ▼
[Fulfillment Service]
  - Receives carrier webhook (or simulates)
  - Publishes OrderDelivered
        │
        ▼
[Order Service]
  - Updates status → DELIVERED
```

---

## Failure Scenarios and Compensations

### Stock Unavailable

```
[Inventory Service]
  - Listens OrderCreated
  - Stock insufficient for one or more SKUs
  - Publishes StockUnavailable

[Order Service]
  - Listens StockUnavailable
  - Updates status → CANCELLED
  - Publishes OrderCancelled

[Notification Service]
  - Listens OrderCancelled
  - Sends "Sorry, item out of stock" email
```

### Payment Failed

```
[Payment Service]
  - Stripe call fails / card declined
  - Publishes PaymentFailed

[Inventory Service]
  - Listens PaymentFailed
  - Releases stock reservations (compensation)
  - Publishes StockReleased

[Order Service]
  - Listens PaymentFailed
  - Updates status → PAYMENT_FAILED

[Notification Service]
  - Sends "Payment could not be processed" email with retry link
```

### Fulfillment Failure

```
[Fulfillment Service]
  - WMS system unreachable after retries
  - Publishes FulfillmentFailed

[Order Service]
  - Updates status → PROCESSING (stuck)
  - Ops team alerted via monitoring (SLA breach alert)
  - Manual override possible via admin API
```

---

## Order Cancellation Flow

Customers can cancel orders in `PENDING`, `PAYMENT_PROCESSING`, or `CONFIRMED` states only.

```
Customer DELETEs /orders/{id}
        │
[Order Service]
  - Validates state is cancellable
  - Updates status → CANCELLED
  - Publishes OrderCancelled
        │
  ┌─────┴────────────────────────┐
  │                              │
  ▼                              ▼
[Inventory Service]      [Payment Service]
  - Releases reservations  - If payment was captured → trigger refund
                           - Publishes RefundInitiated
                                   │
                                   ▼
                           [Notification Service]
                             - Sends refund confirmation
```

---

## Return / Refund Flow

Only possible for `DELIVERED` orders within 30 days.

```
Customer POSTs /orders/{id}/returns
        │
[Order Service]
  - Validates eligibility (status = DELIVERED, within 30 days)
  - Updates status → RETURN_REQUESTED
  - Publishes ReturnRequested
        │
[Fulfillment Service]
  - Creates return shipment label
  - Publishes ReturnLabelCreated
        │
[Notification Service]
  - Sends return label via email
        │
  [Customer ships item back]
        │
[Fulfillment Service]
  - Receives return arrival event (webhook or manual)
  - Publishes ReturnReceived
        │
[Payment Service]
  - Initiates refund via Stripe
  - Publishes RefundCompleted
        │
[Order Service]
  - Updates status → REFUNDED
```

---

## Event Schema Standards

All Kafka events share a common envelope:

```json
{
  "eventId": "uuid",
  "eventType": "OrderCreated",
  "aggregateType": "Order",
  "aggregateId": "order-uuid",
  "occurredAt": "2026-06-04T10:00:00Z",
  "version": 1,
  "payload": { ... }
}
```

- `eventId` is used for consumer deduplication
- `version` allows schema evolution
- `occurredAt` is set by the publisher (not Kafka's timestamp)

---

## Idempotency Requirements

| Consumer | Key for deduplication | Storage |
|---|---|---|
| Inventory — reserve stock | `eventId` | Redis with 24h TTL |
| Payment — capture | `orderId` (unique payment per order) | DB UNIQUE constraint |
| Fulfillment — create job | `orderId` | DB UNIQUE constraint |
| Notification — send email | `eventId + recipientId` | Redis with 48h TTL |

---

## Timeout and SLA Enforcement

A scheduled job (every 5 minutes) checks for:

| Condition | Action |
|---|---|
| Order in `PENDING` > 5 minutes | Alert ops, auto-cancel if no event received |
| Stock reservation not confirmed after 10 minutes | Release reservation |
| Order in `PROCESSING` > 48 hours | SLA breach alert to ops team |
| Delivery not confirmed 14 days after ship date | Trigger investigation workflow |
