# OMS-202 — Orders API Times Out Under Normal Load

| Field       | Value                          |
|-------------|--------------------------------|
| Priority    | P1 — Critical                  |
| Reporter    | Dev Ops — Alerting System      |
| Assignee    | Unassigned                     |
| Created     | 2026-06-26 15:10 SGT           |
| Environment | Production                     |
| Service     | order-service                  |

---

## Alert Triggered

> **PagerDuty:** `order-service` p99 latency exceeded 5000ms threshold.
> Error rate spiked from 0.1% → 38% at 15:08 SGT.

---

## Description

Starting at approximately 15:08 SGT, users began reporting that order creation
is extremely slow or timing out completely. The issue occurs when more than one
request hits the service at the same time. Single requests still work but take
longer than usual.

Frontend team reports the spinner runs for 10+ seconds before showing an error.

---

## Steps to Reproduce

Send 3 concurrent order creation requests:

```bash
TOKEN="<your-jwt-token>"

for i in 1 2 3; do
  curl -s -X POST http://localhost:8082/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: $(uuidgen)" \
    -d '{
      "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
      "lineItems": [{"productId": "11100000-0000-0000-0000-000000000001",
        "sku": "SHIRT-RED-L", "productName": "Red T-Shirt",
        "quantity": 1, "unitPrice": 29.99}],
      "shippingName": "John", "shippingLine1": "123 St",
      "shippingCity": "Singapore", "shippingState": "SG",
      "shippingZip": "018989", "shippingCountry": "SG"
    }' &
done; wait
```

---

## Expected Behaviour

All 3 requests complete within 500ms.

## Actual Behaviour

1st request succeeds. 2nd and 3rd requests time out after ~500ms with:

```json
HTTP 500
{
  "timestamp": "2026-06-26T07:10:45.001+00:00",
  "status": 500,
  "error": "Internal Server Error"
}
```

Server logs show:

```
2026-06-26 15:10:45,002 [order-service] ERROR - HikariPool-1 - Connection is not
available, request timed out after 500ms.

org.springframework.dao.DataAccessResourceFailureException:
Unable to acquire JDBC Connection [HikariPool-1 - Connection is not available,
request timed out after 500ms]
  at org.springframework.orm.jpa.vendor.HibernateJpaDialect.translateExceptionIfPossible
```

---

## Metrics

```bash
# Check these Actuator endpoints
curl http://localhost:8082/actuator/metrics/hikaricp.connections.max
curl http://localhost:8082/actuator/metrics/hikaricp.connections.active
curl http://localhost:8082/actuator/metrics/hikaricp.connections.pending
curl http://localhost:8082/actuator/metrics/hikaricp.connections.timeout
```

Expected `hikaricp.connections.max` to be 10. Check what it actually is.

---

## Impact

- Any traffic surge (flash sale, marketing campaign) will cause widespread failures
- SLA breach — p99 latency target is 500ms, currently 10,000ms+

---

## Debugging Steps to Try

```bash
# 1. Check current pool config loaded by the running service
curl -s http://localhost:8082/actuator/env | jq '
  .propertySources[]
  | select(.name | contains("application"))
  | .properties
  | to_entries[]
  | select(.key | contains("hikari") or contains("pool"))
'

# 2. Check pool metrics during load
watch -n 1 'curl -s http://localhost:8082/actuator/metrics/hikaricp.connections.active | jq .measurements'

# 3. Find the config in the codebase
grep -n "maximum-pool-size\|connection-timeout" order-service/src/main/resources/application.yml
```

---

## Acceptance Criteria

- [ ] `hikaricp.connections.max` reports 10 per shard
- [ ] 10 concurrent requests all complete successfully
- [ ] p99 latency returns below 500ms under load
