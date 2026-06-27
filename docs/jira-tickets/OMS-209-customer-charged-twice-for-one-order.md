# OMS-209 — Customer Charged Twice for a Single Order

| Field       | Value                                       |
|-------------|---------------------------------------------|
| Priority    | P1 — Critical                               |
| Reporter    | Customer Support — escalated from Wei Lin   |
| Assignee    | Unassigned                                  |
| Created     | 2026-06-26 20:00 SGT                        |
| Environment | Production                                  |
| Service     | payment-service (Kafka consumer)            |

---

## Customer Report

> "I was charged twice for the same order! I can see two identical transactions
> on my bank statement both for $59.98. My order history only shows one order
> but my credit card was definitely charged twice. I want a refund immediately."
>
> — Customer ticket #49318, submitted 19:50 SGT

4 similar double-charge complaints received today. All occurred during the
14:00–15:00 SGT window when the payment-service pod was restarted due to a
memory alert.

---

## What We Know

- All affected orders have exactly one entry in the `orders` table
- But the payment gateway shows two successful charge attempts for the same order
- The double charges all happened during a ~10-minute window when payment-service
  was restarted
- No duplicate orders in the database — idempotency at the order level worked
- The problem is at the payment processing level

---

## Steps to Reproduce (Engineering)

```bash
# 1. Place an order, note the order ID
TOKEN="<your-jwt-token>"
ORDER_ID=$(curl -s -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
    "lineItems": [{"productId": "11100000-0000-0000-0000-000000000001",
      "sku": "SHIRT-RED-L", "productName": "Red T-Shirt",
      "quantity": 1, "unitPrice": 29.99}],
    "shippingName": "Wei Lin", "shippingLine1": "5 Boat Quay",
    "shippingCity": "Singapore", "shippingState": "SG",
    "shippingZip": "049422", "shippingCountry": "SG"
  }' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo "Order: $ORDER_ID"

# 2. While payment-service is processing, kill and restart it
docker restart oms-payment-service

# 3. Check payment gateway for duplicate charges on this order
```

---

## Expected Behaviour

Payment is charged exactly once per order, even if payment-service restarts
mid-processing.

## Actual Behaviour

Payment charged twice. The first charge was initiated before the restart.
On restart, Kafka redelivered the same `OrderCreated` event (offset not committed)
and payment-service processed it again.

---

## What Is Happening (Engineering Context)

Kafka's at-least-once delivery guarantee means: if a consumer crashes before
committing its offset, the message will be redelivered on restart.

```
Timeline:
  T1: payment-service receives OrderCreated event for order-abc
  T2: payment-service calls payment gateway → charge succeeds ($59.98)
  T3: payment-service crashes BEFORE committing Kafka offset
  T4: payment-service restarts
  T5: Kafka redelivers the same OrderCreated event (offset was never committed)
  T6: payment-service calls payment gateway again → second charge ($59.98)
```

The fix requires **idempotent payment processing** — before charging, check if
this order has already been paid.

---

## Debugging Steps (Engineering)

```bash
# 1. Check if payment has been recorded for this order before processing
docker exec oms-postgres psql -U oms -d payments_db -c \
  "SELECT order_id, amount, status, created_at FROM payments
   WHERE order_id = '<affected-order-id>';"

# If 2 rows exist for the same order_id — confirmed double charge.

# 2. Check consumer group offsets to see if offset was committed
docker exec oms-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group payment-service

# 3. Check payment-service for idempotency check before charging
grep -rn "idempotent\|findByOrderId\|alreadyPaid\|existsByOrderId" \
  payment-service/src/main/java/

# 4. Check Kafka consumer commit mode
grep -n "ack-mode\|enable.auto.commit\|AUTO_COMMIT" \
  payment-service/src/main/resources/application.yml
```

---

## Root Cause

Payment-service uses **auto-commit** for Kafka offsets
(`enable.auto.commit=true`). When the service crashes between charging and
the next auto-commit interval, Kafka redelivers the event.

The service also has **no idempotency check** before calling the payment
gateway — it doesn't verify whether this order has already been charged.

**Fix:**
1. Switch to **manual offset commit** — only commit after the payment is
   successfully recorded in the database
2. Add an idempotency check: `if (paymentRepository.existsByOrderId(orderId)) return;`
3. Use the order ID as the payment gateway's idempotency key so even if called
   twice, the gateway deduplicates it

---

## Impact

- Customers double charged — immediate refund required
- Legal and compliance risk
- Loss of customer trust

---

## Acceptance Criteria

- [ ] Restarting payment-service mid-processing does not cause duplicate charges
- [ ] `payments` table has at most one row per `order_id`
- [ ] Payment gateway idempotency key set to order ID
- [ ] Kafka offset committed only after payment is durably recorded
