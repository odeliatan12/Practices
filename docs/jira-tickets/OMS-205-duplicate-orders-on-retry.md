# OMS-205 — Duplicate Orders Created on Network Retry

| Field       | Value                          |
|-------------|--------------------------------|
| Priority    | P1 — Critical                  |
| Reporter    | Marcus Wong (Backend Engineer) |
| Assignee    | Unassigned                     |
| Created     | 2026-06-26 16:30 SGT           |
| Environment | Production                     |
| Service     | order-service                  |

---

## Description

When a client retries a `POST /api/v1/orders` request (due to a network timeout
or slow response), a **second identical order is being created** instead of
returning the original one. Customers are being double-charged and seeing
duplicate orders in their history.

This contradicts the documented behaviour: the `Idempotency-Key` header is
supposed to prevent duplicates on retry.

---

## Example

Customer `a1b2c3d4-0000-0000-0000-000000000001` placed one order but the
frontend timed out after 3 seconds. The frontend retried automatically.
The customer now has **two identical orders** both in `PENDING` status.

```sql
SELECT id, customer_id, status, total_amount, created_at
FROM orders
WHERE customer_id = 'a1b2c3d4-0000-0000-0000-000000000001'
ORDER BY created_at DESC;

-- Result:
-- id                                   | status  | total_amount | created_at
-- 658514c8-6ed9-4686-b79f-50b8d6544c8b | PENDING | 59.98        | 2026-06-26 08:01:03
-- 5ea74d54-29d1-47cd-91ec-016f1109a3a9 | PENDING | 59.98        | 2026-06-26 08:01:05
-- Two orders, 2 seconds apart — same idempotency key was sent both times
```

---

## Steps to Reproduce

```bash
TOKEN="<your-jwt-token>"

# Send the same request twice with the SAME Idempotency-Key
IDEM_KEY="test-idempotency-key-12345"

curl -s -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{
    "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
    "lineItems": [{"productId": "11100000-0000-0000-0000-000000000001",
      "sku": "SHIRT-RED-L", "productName": "Red T-Shirt",
      "quantity": 1, "unitPrice": 29.99}],
    "shippingName": "John", "shippingLine1": "123 St",
    "shippingCity": "Singapore", "shippingState": "SG",
    "shippingZip": "018989", "shippingCountry": "SG"
  }' | jq .id

# Wait 5 seconds (simulates Redis TTL expiry or cache miss)
sleep 5

# Send the exact same request again with the SAME key
curl -s -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{
    "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
    "lineItems": [{"productId": "11100000-0000-0000-0000-000000000001",
      "sku": "SHIRT-RED-L", "productName": "Red T-Shirt",
      "quantity": 1, "unitPrice": 29.99}],
    "shippingName": "John", "shippingLine1": "123 St",
    "shippingCity": "Singapore", "shippingState": "SG",
    "shippingZip": "018989", "shippingCountry": "SG"
  }' | jq .id

# The two IDs should be identical but they are different
```

---

## Expected Behaviour

Both requests return the **same order ID**. The second request is a no-op.

## Actual Behaviour

Both requests return **different order IDs**. Two orders exist in the database.

---

## Suspected Root Cause

The idempotency check in `OrderService.createOrder()` reads from Redis:

```java
String redisKey = "idempotency:order:" + idempotencyKey;
Order cached = redisTemplate.opsForValue().get(redisKey);
```

If the Redis entry has expired or was never written, the check misses and a
new order is created. Check:

1. Is the order being written back to Redis after creation?
2. What is the TTL set on the Redis key?
3. Is Redis `setIfAbsent` being used correctly?

---

## Debugging Steps to Try

```bash
# 1. Check if the idempotency key is being stored in Redis after order creation
docker exec -it oms-redis redis-cli -p 6379
> KEYS idempotency:order:*
> TTL idempotency:order:<your-key>

# 2. Trace the code path
grep -n "idempotency\|redisKey\|setIfAbsent\|opsForValue" \
  order-service/src/main/java/com/oms/order/service/OrderService.java

# 3. Check Redis connection and health
curl -s http://localhost:8082/actuator/health | jq .components.redis
```

---

## Impact

- Customers being double-charged
- Duplicate orders requiring manual cancellation by support
- Inventory incorrectly reserved twice for same customer

---

## Acceptance Criteria

- [ ] Sending the same `Idempotency-Key` twice returns the same order ID
- [ ] No duplicate rows in `orders` table for same idempotency key
- [ ] Redis key `idempotency:order:{key}` exists after order creation with appropriate TTL
