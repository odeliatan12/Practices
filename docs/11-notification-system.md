# 11 — Notification System

## Overview

The Notification Service is a pure event consumer — it never initiates business logic. It listens to Kafka events from other services and translates them into customer-facing messages (email, SMS) and internal alerts (ops team).

---

## Channels

| Channel | Provider | Use Case |
|---|---|---|
| Email | SendGrid | Order confirmations, status updates, receipts, return labels |
| SMS | Twilio | Shipping confirmation with tracking link, delivery confirmation |
| WebSocket | Spring WebSocket | Real-time UI updates (see doc 09) |
| Webhook | HTTP POST | Ops team integrations (Slack, PagerDuty) |

For local development, use stubs that log to console instead of calling real APIs.

---

## Event → Notification Mapping

| Kafka Event | Recipient | Email | SMS |
|---|---|---|---|
| `OrderCreated` | Customer | "We received your order" | — |
| `PaymentFailed` | Customer | "Payment failed — retry link" | — |
| `OrderCancelled` (stock) | Customer | "Item out of stock — order cancelled" | — |
| `PaymentCaptured` | Customer | "Order confirmed — summary" | — |
| `OrderShipped` | Customer | "Your order is on the way" | "Shipped! Track: {url}" |
| `OrderDelivered` | Customer | "Your order arrived — leave a review" | "Delivered!" |
| `ReturnLabelCreated` | Customer | "Return label attached" | — |
| `RefundCompleted` | Customer | "Refund of ${amount} issued" | — |
| `LowStockAlert` | Ops team | "SKU {sku} is low on stock" | — |
| `SLABreached` | Ops team | "Order {id} stuck in PROCESSING" | — |

---

## Service Architecture

```
notification-service/
├── consumer/
│   ├── OrderEventConsumer.java       ← listens to orders topic
│   ├── PaymentEventConsumer.java     ← listens to payments topic
│   ├── InventoryEventConsumer.java   ← listens to inventory topic
│   └── FulfillmentEventConsumer.java ← listens to fulfillment topic
├── dispatcher/
│   └── NotificationDispatcher.java  ← decides channel per event type
├── template/
│   └── EmailTemplateService.java    ← renders HTML email templates
├── channel/
│   ├── EmailChannel.java            ← SendGrid integration
│   ├── SmsChannel.java              ← Twilio integration
│   └── WebhookChannel.java          ← HTTP POST to Slack/PagerDuty
├── dedup/
│   └── NotificationDeduplicator.java ← Redis-based event ID dedup
└── log/
    └── NotificationLog.java         ← records sent notifications
```

---

## Idempotency

Notifications must not be sent twice for the same event (e.g., if the service crashes after sending but before committing the Kafka offset).

```
Before sending:
  SETNX Redis key: notif:{eventId}:{customerId}:{channel}
  TTL: 48 hours

  If key already exists → skip (already sent)
  If not → send → mark in Redis
```

The `notif:` Redis key encodes `eventId + customerId + channel` so a retry of the same event to the same person on the same channel is skipped, but a different channel is allowed.

---

## Email Templates

Templates stored as HTML files with Thymeleaf placeholders.

Template list:
```
templates/
├── order-received.html
├── payment-failed.html
├── order-confirmed.html
├── order-shipped.html
├── order-delivered.html
├── return-label.html
├── refund-issued.html
└── base-layout.html       ← header, footer, branding
```

Template data is populated from the Kafka event payload — no DB calls needed.

---

## Notification Log

Every sent notification is recorded:

```
notification_log (table in notification service DB):
  id              UUID
  event_id        UUID        ← Kafka event that triggered this
  event_type      VARCHAR
  recipient_id    UUID
  channel         VARCHAR     ← email | sms | webhook
  destination     VARCHAR     ← email address or phone number
  status          VARCHAR     ← SENT | FAILED | SKIPPED
  error_message   TEXT
  sent_at         TIMESTAMPTZ
```

Used for:
- Support team: "Did the customer receive a shipping notification?"
- Debugging delivery failures
- Compliance audit trail

---

## Customer Notification Preferences

Customers can opt out of SMS or marketing emails.

Preferences stored in User Service. Notification Service calls User Service (HTTP) on startup to cache preferences, or checks on each notification.

```
If customer.smsEnabled = false → skip SMS channel
If event is marketing (post-delivery review request) and customer.marketingOptIn = false → skip
```

---

## Ops Alerts

For internal ops notifications (low stock, SLA breach):

- Slack webhook: POST JSON message to configured Slack channel URL
- PagerDuty: POST to Events API v2 for P1 SLA breaches
- These are configured in `application.yml` and loaded as beans — switchable per environment

---

## Retry and Error Handling

- SendGrid / Twilio calls: retry up to 3 times with 1s / 2s / 4s backoff
- On persistent failure: log to `notification_log` with `status = FAILED`
- Kafka offset not committed until all notification attempts complete
- Dead-letter queue if the Kafka message itself cannot be parsed

---

## Local Development

Instead of real SendGrid/Twilio:
- `EmailChannel` stub: logs email content to console
- `SmsChannel` stub: logs SMS to console
- Enable with Spring profile: `notification.mode=stub`
