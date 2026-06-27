# OMS-201 — Cancel Order Returns 500 Internal Server Error

| Field       | Value                        |
|-------------|------------------------------|
| Priority    | P1 — Critical                |
| Reporter    | Sarah Lim (Customer Support) |
| Assignee    | Unassigned                   |
| Created     | 2026-06-26 14:32 SGT         |
| Environment | Production                   |
| Service     | order-service                |

---

## Customer Report

> "I tried to cancel my order but kept getting an error. I refreshed and tried again
> three times. The page just shows 'Something went wrong'. My order is still active
> and I don't want to be charged."
>
> — Customer #a1b2c3d4-0000-0000-0000-000000000001

Support ticket volume: **23 similar complaints in the last 2 hours.**

---

## Steps to Reproduce

1. Create a new order without filling in the **Order Notes** field (leave it blank)
2. Wait for order to reach `PENDING` status
3. Call `PATCH /api/v1/orders/{orderId}/cancel?customerId={customerId}`
4. Observe 500 response

Orders created **with** notes cancel successfully.
Orders created **without** notes always return 500.

---

## Expected Behaviour

```json
HTTP 200 OK
{
  "id": "658514c8-6ed9-4686-b79f-50b8d6544c8b",
  "status": "CANCELLED",
  ...
}
```

## Actual Behaviour

```json
HTTP 500 Internal Server Error
{
  "timestamp": "2026-06-26T06:35:12.123+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/api/v1/orders/658514c8-6ed9-4686-b79f-50b8d6544c8b/cancel"
}
```

---

## Server Logs

```
2026-06-26 14:35:12,441 [order-service] ERROR [correlationId=4a7f50c0-9ee9-4a82-8f0d-e36859f7d07a]
o.a.c.c.C.[.[.[.[dispatcherServlet] - Servlet.service() for servlet [dispatcherServlet]
threw exception [Request processing failed:
java.lang.NullPointerException: Cannot invoke "String.trim()" because the
return value of "com.oms.order.domain.Order.getNotes()" is null]
  at com.oms.order.service.OrderService.cancelOrder(OrderService.java:159)
  at com.oms.order.controller.OrderController.cancelOrder(OrderController.java:64)
  ...
```

---

## Impact

- All cancel requests for orders without notes are failing
- Estimated **~70% of orders** are created without notes (notes is optional)
- Customers are unable to cancel — risk of chargebacks and complaints

---

## Debugging Steps to Try

```bash
# 1. Reproduce the error
curl -s -X PATCH "http://localhost:8082/api/v1/orders/{ORDER_ID}/cancel?customerId={CUSTOMER_ID}" | jq .

# 2. Check the stack trace in logs
docker logs oms-order-service 2>&1 | grep -A 20 "NullPointerException"

# 3. Find the offending line
grep -n "getNotes\|trim\|auditNote" order-service/src/main/java/com/oms/order/service/OrderService.java
```

---

## Acceptance Criteria

- [ ] `PATCH /cancel` returns 200 for orders with null notes
- [ ] `PATCH /cancel` returns 200 for orders with empty notes
- [ ] `PATCH /cancel` returns 200 for orders with non-empty notes
- [ ] No NullPointerException in logs after fix
