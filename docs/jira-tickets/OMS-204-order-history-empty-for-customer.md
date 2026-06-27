# OMS-204 — Customer Order History Shows Empty on Frontend

| Field       | Value                         |
|-------------|-------------------------------|
| Priority    | P2 — High                     |
| Reporter    | Rachel Tan (QA Engineer)      |
| Assignee    | Unassigned                    |
| Created     | 2026-06-26 16:00 SGT          |
| Environment | Production                    |
| Service     | order-service, api-gateway    |

---

## Description

Customers report that their "My Orders" page on the frontend is blank even though
they have placed orders previously. The page loads successfully (no error shown)
but the order list is empty.

Calling the API directly returns an empty `currentOrders` and `pastOrders` list
even for customers with confirmed orders in the database.

---

## Steps to Reproduce

1. Create several orders for customer `a1b2c3d4-0000-0000-0000-000000000001`
   with different statuses (`PENDING`, `CONFIRMED`, `DELIVERED`)
2. Call `GET /api/v1/orders/customer/{customerId}`
3. Observe empty lists in response

```bash
TOKEN="<your-jwt-token>"

curl -s http://localhost:8082/api/v1/orders/customer/a1b2c3d4-0000-0000-0000-000000000001 | jq .
```

---

## Expected Behaviour

```json
HTTP 200 OK
{
  "currentOrders": [
    { "id": "...", "status": "PENDING", ... },
    { "id": "...", "status": "CONFIRMED", ... }
  ],
  "pastOrders": [
    { "id": "...", "status": "DELIVERED", ... }
  ]
}
```

## Actual Behaviour

```json
HTTP 200 OK
{
  "currentOrders": [],
  "pastOrders": []
}
```

No errors in logs. The query runs, returns no results.

---

## Database Check

Connecting to the database directly **confirms the orders exist**:

```sql
SELECT id, customer_id, status FROM orders
WHERE customer_id = 'a1b2c3d4-0000-0000-0000-000000000001';

-- Returns rows! So data IS in the database.
```

---

## Suspected Area

The `getOrders()` method in `OrderService` queries by `customerId`. The UUID
passed into the query may not match what's stored — check how `customerId`
is extracted from the JWT and forwarded from the gateway.

Look at how `AuthFilter` injects `X-User-Id` and whether the controller
reads from the path variable or the header.

---

## Debugging Steps to Try

```bash
# 1. Confirm orders exist in DB for this customer
docker exec -it oms-postgres psql -U oms -d orders_db -c \
  "SELECT id, customer_id, status, created_at FROM orders
   WHERE customer_id = 'a1b2c3d4-0000-0000-0000-000000000001'
   ORDER BY created_at DESC;"

# 2. Call the endpoint and check the raw response
curl -sv http://localhost:8082/api/v1/orders/customer/a1b2c3d4-0000-0000-0000-000000000001

# 3. Enable DEBUG logging temporarily to see the SQL query
curl -s -X POST http://localhost:8082/actuator/loggers/org.hibernate.SQL \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'

# Then run the request again and check logs for the actual SQL
docker logs oms-order-service --tail 50 2>&1 | grep -A 3 "select\|SELECT"

# 4. Check the query in the repository
grep -n "findByCustomerId" order-service/src/main/java/com/oms/order/repository/OrderRepository.java
```

---

## Impact

- All customers see an empty order history
- "My Orders" page appears broken
- Customers contacting support thinking their orders were lost

---

## Acceptance Criteria

- [ ] `GET /api/v1/orders/customer/{customerId}` returns correct orders grouped by status
- [ ] `currentOrders` contains PENDING, CONFIRMED, PROCESSING, SHIPPED orders
- [ ] `pastOrders` contains DELIVERED, CANCELLED, REFUNDED orders
