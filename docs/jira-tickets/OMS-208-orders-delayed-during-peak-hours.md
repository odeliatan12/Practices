# OMS-208 — Orders Taking Too Long to Process During Peak Hours

| Field       | Value                                      |
|-------------|---------------------------------------------|
| Priority    | P2 — High                                   |
| Reporter    | Customer Support — multiple complaints      |
| Assignee    | Unassigned                                  |
| Created     | 2026-06-26 19:00 SGT                        |
| Environment | Production                                  |
| Service     | payment-service (Kafka consumer)            |

---

## Customer Report

> "I placed my order over 20 minutes ago and it still says 'Confirmed'. Usually
> I get a payment confirmation within a minute. Is something wrong?"
>
> — Customer ticket #49230, submitted 18:55 SGT

9 similar reports received between 18:00–19:00 SGT, coinciding with the
evening peak shopping window. Orders eventually process but with a 15–30 minute
delay instead of the usual under-1-minute.

No orders appear to be lost — they are just very slow.

---

## What We Know

- Orders are being created and confirmed normally
- Payment eventually goes through — no revenue loss
- Delay only happens during peak hours (18:00–21:00 SGT)
- Off-peak orders process in under 60 seconds as expected
- Only started being reported after the marketing campaign launched this week
  (campaign drove a 5× spike in order volume)

---

## Steps to Reproduce (Engineering)

Simulate peak load and observe consumer lag growing:

```bash
# Send 50 orders rapidly
TOKEN="<your-jwt-token>"
for i in $(seq 1 50); do
  curl -s -X POST http://localhost:8082/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: load-test-$i-$(date +%s)" \
    -d '{
      "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
      "lineItems": [{"productId": "11100000-0000-0000-0000-000000000001",
        "sku": "SHIRT-RED-L", "productName": "Red T-Shirt",
        "quantity": 1, "unitPrice": 29.99}],
      "shippingName": "Load Test", "shippingLine1": "123 St",
      "shippingCity": "Singapore", "shippingState": "SG",
      "shippingZip": "018989", "shippingCountry": "SG"
    }' > /dev/null &
done; wait
```

---

## Expected Behaviour

Consumer processes messages at the same rate they arrive. Lag stays near 0.

## Actual Behaviour

Consumer falls behind. Lag grows during peak, messages queue up, orders delayed.

---

## Debugging Steps (Engineering)

```bash
# 1. Check current consumer group lag on the orders topic
docker exec oms-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group payment-service

# Output columns to watch:
# CONSUMER-ID   HOST   CLIENT-ID   TOPIC   PARTITION   CURRENT-OFFSET   LOG-END-OFFSET   LAG
# LAG = LOG-END-OFFSET - CURRENT-OFFSET
# Growing LAG means consumer is falling behind

# 2. Check how many consumer instances are running
docker exec oms-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group payment-service \
  | grep -v "^$" | awk '{print $1, $2}' | sort -u

# 3. Check how many partitions the orders topic has
docker exec oms-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe --topic orders

# KEY INSIGHT: number of consumers cannot exceed number of partitions
# If topic has 1 partition, only 1 consumer can process at a time

# 4. Monitor lag in real time (run this, then send orders in another terminal)
watch -n 2 'docker exec oms-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group payment-service 2>/dev/null | grep orders'

# 5. Check payment-service throughput metrics
curl -s http://localhost:8083/actuator/metrics/kafka.consumer.records-consumed-rate \
  | python3 -m json.tool
```

---

## Root Cause (Most Likely)

The `orders` Kafka topic was created with **1 partition**. This means only
1 consumer instance can process messages at a time, regardless of how many
payment-service instances are running.

During normal load (2–3 orders/min) this is fine.
During peak load (50+ orders/min) the single consumer can't keep up.

**Fix:**
1. Increase the number of partitions on the `orders` topic (allows parallelism)
2. Scale up payment-service consumer instances to match partition count
3. Set `max.poll.records` and `fetch.max.bytes` appropriately for throughput

**Check current partition count:**
```bash
docker exec oms-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe --topic orders
```

---

## Impact

- 15–30 minute payment delays during peak hours
- Customer satisfaction degraded
- Risk of cart abandonment if customers think payment failed

---

## Acceptance Criteria

- [ ] Consumer lag stays below 100 messages during peak load simulation
- [ ] Orders processed within 60 seconds even under 5× normal volume
- [ ] `orders` topic has sufficient partitions for expected peak concurrency
