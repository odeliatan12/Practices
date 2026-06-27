# OMS-207 — Payment Not Triggered After Order is Placed

| Field       | Value                                        |
|-------------|----------------------------------------------|
| Priority    | P1 — Critical                                |
| Reporter    | Customer Support — escalated from James Tan  |
| Assignee    | Unassigned                                   |
| Created     | 2026-06-26 18:00 SGT                         |
| Environment | Production                                   |
| Service     | order-service, payment-service               |

---

## Customer Report

> "I placed an order and received an order confirmation number. But I never got
> a payment receipt and my bank says nothing was charged. The order still shows
> as 'Confirmed' on the website but I'm worried it will be cancelled."
>
> — Customer ticket #49102, submitted 17:45 SGT

Support has received 8 similar reports since approximately 16:00 SGT — all from
orders placed after the v2.4.0 deployment at 15:50 SGT.

Orders placed before 15:50 SGT processed normally.

---

## What We Know

- Orders are being created and moving to `CONFIRMED` status
- Payment is never initiated — customers are not charged
- No error shown to the customer
- Issue started immediately after the v2.4.0 deployment
- v2.4.0 included a change to the `OrderCreated` event payload (added `discountCode` field)

---

## Steps to Reproduce (Engineering)

1. Place a new order via `POST /api/v1/orders`
2. Confirm order reaches `CONFIRMED` status
3. Check payment-service logs — consumer is receiving messages but immediately failing
4. Check the dead letter topic on payment-service side

---

## Expected Behaviour

- `OrderCreated` event published to `orders` topic
- payment-service consumes and deserialises the event successfully
- Payment initiated within 10 seconds of order confirmation

## Actual Behaviour

- Event is published successfully (no error on order-service side)
- payment-service consumer throws a deserialisation exception
- Message is skipped or dead-lettered on the consumer side
- Order stays `CONFIRMED` but payment never runs

---

## Relevant Logs

**order-service** — no errors, event published fine:
```
INFO  [correlationId=abc123] c.o.o.event.producer.OutboxPoller
  — Event id=abc... published successfully to topic orders
```

**payment-service** — error on the consumer side:
```
ERROR c.o.p.kafka.OrderEventConsumer — Failed to deserialise message from topic orders
com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException:
  Unrecognized field "discountCode" (class com.oms.payment.dto.OrderCreatedEvent)
  at [Source: (byte[]); line: 1, column: 210]
```

The field `discountCode` was added to the producer (order-service v2.4.0) but
payment-service has not yet been updated to accept it.

---

## Debugging Steps (Engineering)

```bash
# 1. Check order-service outbox — confirm events ARE being published
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT id, published_at, retry_count FROM outbox_events ORDER BY created_at DESC LIMIT 5;"

# 2. Check what the published event payload looks like
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT payload FROM outbox_events ORDER BY created_at DESC LIMIT 1;"

# 3. Check payment-service consumer logs for deserialisation errors
docker logs oms-payment-service 2>&1 | grep -i "deserialise\|unrecognized\|jackson\|error" | tail -20

# 4. Check if payment-service has DeserializationExceptionHandler configured
grep -rn "DeserializationException\|FAIL\|IGNORE\|dead.letter" \
  payment-service/src/main/resources/application.yml

# 5. Check the consumer group lag on the orders topic
docker exec oms-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group payment-service
```

---

## Root Cause

Schema mismatch between producer and consumer:
- order-service v2.4.0 added `discountCode` to `OrderCreatedEvent`
- payment-service still uses the old schema with no `discountCode` field
- Jackson strict deserialisation rejects unknown fields

**Fix options:**
1. Add `@JsonIgnoreProperties(ignoreUnknown = true)` to payment-service's `OrderCreatedEvent` DTO (short-term)
2. Deploy updated payment-service that understands the new field (proper fix)
3. Roll back order-service v2.4.0 if payment is more critical (emergency)

---

## Impact

- All orders placed after 15:50 SGT are not being charged
- Revenue loss accumulates every minute
- Customers may cancel and go to a competitor thinking payment failed

---

## Acceptance Criteria

- [ ] payment-service consumer processes `OrderCreated` events without deserialisation errors
- [ ] Consumer group lag on `orders` topic returns to 0
- [ ] Payment is initiated within 10 seconds of order confirmation
- [ ] Schema compatibility tested before future event payload changes
