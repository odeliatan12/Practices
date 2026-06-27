# OMS-203 — Orders Not Being Processed After Placement

| Field       | Value                                    |
|-------------|------------------------------------------|
| Priority    | P1 — Critical                            |
| Reporter    | Customer Support — escalated from Priya  |
| Assignee    | Unassigned                               |
| Created     | 2026-06-26 15:45 SGT                     |
| Environment | Production                               |
| Service     | order-service                            |

---

## Customer Report

> "I placed an order about an hour ago and I still haven't received a confirmation
> email. My order page just shows 'Pending' and nothing has changed. I tried
> placing the order again and the same thing happened. My card has not been charged
> either. Can you please look into this?"
>
> — Customer ticket #48821, submitted 15:30 SGT

Multiple customers reporting the same issue since approximately 14:00 SGT.
Customer support has received 12 similar complaints in the past 90 minutes.

---

## What We Know

- Orders appear to go through — customers get a success screen and an order ID
- But nothing happens after that — no payment, no confirmation email
- Refreshing the order status page shows "Pending" indefinitely
- Issue seems to have started after the 14:00 SGT deployment

No error is shown to the customer. From their side the order just silently stalls.

---

## Steps to Reproduce (Engineering)

1. Create an order via `POST /api/v1/orders`
2. Confirm 201 response with `status: PENDING`
3. Wait 60 seconds
4. Check the order status — it remains `PENDING` indefinitely
5. Check outbox table in database — events have `published_at = NULL` with increasing `retry_count`

```bash
# Check outbox events stuck in retry loop
# Connect to postgres and run:
docker exec -it oms-postgres psql -U oms -d orders_db -c \
  "SELECT id, event_type, retry_count, published_at, dead_lettered_at
   FROM outbox_events
   ORDER BY created_at DESC
   LIMIT 10;"
```

---

## Expected Behaviour

- `outbox_events.published_at` is set within 5 seconds of order creation
- payment-service consumer receives event on `orders` topic
- Order status transitions from `PENDING` → `PAYMENT_PROCESSING`

## Actual Behaviour

- `outbox_events.published_at` remains `NULL`
- `retry_count` increments every 5 seconds
- After 3 retries, `dead_lettered_at` is set and event is abandoned

Logs show repeated failures:

```
2026-06-26 15:46:03,112 [order-service] WARN [correlationId=abc123]
c.o.o.event.producer.OutboxPoller - Failed to publish event id=f8c2... (attempt 1/3)
org.apache.kafka.common.errors.TimeoutException: Topic orders not present in
metadata after 60000 ms.

2026-06-26 15:46:08,115 [order-service] WARN [correlationId=abc123]
c.o.o.event.producer.OutboxPoller - Failed to publish event id=f8c2... (attempt 2/3)

2026-06-26 15:46:13,118 [order-service] ERROR [correlationId=abc123]
c.o.o.event.producer.OutboxPoller - Event id=f8c2... dead-lettered after 3 retries
```

---

## Key Observation

The error says `Topic orders not present in metadata`. This suggests order-service
**cannot reach the Kafka broker** at all — it's not a topic configuration issue
on the consumer side.

---

## Debugging Steps to Try

```bash
# 1. Check what Kafka broker address the service is configured to connect to
curl -s http://localhost:8082/actuator/env | jq '
  .propertySources[]
  | select(.name | contains("application"))
  | .properties
  | to_entries[]
  | select(.key | contains("kafka.bootstrap"))
'

# 2. Verify the correct Kafka broker port
docker exec oms-kafka kafka-broker-api-versions --bootstrap-server localhost:9092

# 3. Check the config file directly
grep -n "bootstrap-servers" order-service/src/main/resources/application.yml

# 4. Watch the OutboxPoller logs in real time
docker logs oms-order-service -f 2>&1 | grep -i "outbox\|kafka\|publish"
```

---

## Impact

- 100% of orders created after 14:00 SGT have not been processed
- No payments initiated — revenue impact
- Inventory not reserved — risk of overselling
- Customers have not received confirmation emails

---

## Acceptance Criteria

- [ ] `outbox_events.published_at` is populated within 5 seconds of order creation
- [ ] payment-service confirms receipt of `OrderCreated` events
- [ ] No `TimeoutException` or `not present in metadata` errors in logs
- [ ] Retry count stays at 0 for new events
