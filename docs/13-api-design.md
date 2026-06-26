# 13 — API Design

## Design Principles

- RESTful resources with plural nouns
- Consistent response envelope
- RFC 7807 Problem Details for errors
- Cursor-based pagination on list endpoints
- Versioning via URL path (`/api/v1/`)
- Idempotency-Key header on mutating endpoints

---

## Base URL

```
Production:  https://api.oms.example.com/api/v1
Development: http://localhost:8080/api/v1
```

All endpoints require `Authorization: Bearer <token>` unless marked public.

---

## Response Envelope

### Success
```json
{
  "data": { ... },
  "meta": {
    "requestId": "abc-123",
    "timestamp": "2026-06-04T10:00:00Z"
  }
}
```

### Paginated List
```json
{
  "data": [ ... ],
  "pagination": {
    "nextCursor": "eyJpZCI6Mn0=",
    "prevCursor": null,
    "hasMore": true,
    "total": 1520
  },
  "meta": {
    "requestId": "abc-123",
    "timestamp": "2026-06-04T10:00:00Z"
  }
}
```

### Error (RFC 7807)
```json
{
  "type": "https://oms.example.com/problems/validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "One or more fields have validation errors",
  "instance": "/api/v1/orders",
  "errors": [
    { "field": "lineItems[0].quantity", "message": "must be greater than 0" },
    { "field": "shippingAddress.zip", "message": "must not be blank" }
  ],
  "requestId": "abc-123"
}
```

---

## Orders API

### Create Order
```
POST /api/v1/orders
Idempotency-Key: {client-generated-uuid}

Body:
{
  "lineItems": [
    { "sku": "WIDGET-XL-RED", "quantity": 2 }
  ],
  "shippingAddress": {
    "name": "Jane Doe",
    "line1": "123 Main St",
    "city": "Austin",
    "state": "TX",
    "zip": "78701",
    "country": "US"
  },
  "notes": "Leave at door"
}

Response 202 Accepted:
{
  "data": {
    "orderId": "uuid",
    "status": "PENDING",
    "estimatedTotal": 149.99,
    "createdAt": "2026-06-04T10:00:00Z"
  }
}
```

Returns `202 Accepted` (not 201) because order processing is async — the saga continues after response.

### Get Order
```
GET /api/v1/orders/{orderId}

Response 200:
{
  "data": {
    "orderId": "uuid",
    "status": "SHIPPED",
    "customer": { "id": "uuid", "name": "Jane Doe" },
    "lineItems": [
      { "sku": "WIDGET-XL-RED", "productName": "Red Widget XL", "quantity": 2, "unitPrice": 49.99, "subtotal": 99.98 }
    ],
    "shippingAddress": { ... },
    "totalAmount": 149.99,
    "trackingNumber": "1Z999AA10123456784",
    "carrier": "UPS",
    "createdAt": "...",
    "updatedAt": "..."
  }
}
```

### List Orders (Customer)
```
GET /api/v1/orders?cursor={cursor}&limit=20&status=SHIPPED

Response 200: (paginated envelope)
```

### Cancel Order
```
DELETE /api/v1/orders/{orderId}

Body: { "reason": "Changed my mind" }

Response 200:
{ "data": { "orderId": "uuid", "status": "CANCELLED" } }

Error 409 Conflict (if already shipped):
{ "type": "...", "title": "Order Cannot Be Cancelled", "status": 409, "detail": "Orders in SHIPPED status cannot be cancelled" }
```

### Request Return
```
POST /api/v1/orders/{orderId}/returns

Body:
{
  "items": [
    { "lineItemId": "uuid", "quantity": 1, "reason": "Defective" }
  ]
}

Response 202 Accepted:
{ "data": { "returnId": "uuid", "status": "RETURN_REQUESTED" } }
```

### Get Order Status History
```
GET /api/v1/orders/{orderId}/events

Response 200:
{
  "data": [
    { "status": "PENDING",   "occurredAt": "2026-06-04T10:00:00Z", "actor": "customer" },
    { "status": "CONFIRMED", "occurredAt": "2026-06-04T10:00:45Z", "actor": "system"   },
    { "status": "SHIPPED",   "occurredAt": "2026-06-04T14:30:00Z", "actor": "system"   }
  ]
}
```

---

## Inventory API

### Get Stock Level
```
GET /api/v1/inventory/sku/{sku}

Response 200:
{
  "data": {
    "sku": "WIDGET-XL-RED",
    "productName": "Red Widget XL",
    "quantityAvailable": 42,
    "warehouseId": "WH-01"
  }
}
```

### Adjust Stock (Admin)
```
PATCH /api/v1/inventory/sku/{sku}/stock
Roles: ROLE_ADMIN, ROLE_WAREHOUSE

Body: { "adjustment": 50, "reason": "Received new shipment" }
Response 200: { "data": { "sku": "...", "newQuantity": 92 } }
```

### List Low-Stock SKUs (Admin)
```
GET /api/v1/inventory/low-stock?threshold=10
Roles: ROLE_ADMIN

Response 200: paginated list of SKUs below threshold
```

---

## User / Auth API

### Register
```
POST /api/v1/auth/register  [PUBLIC]
Body: { "email", "password", "name" }
Response 201: { "data": { "userId", "email" } }
```

### Login
```
POST /api/v1/auth/login  [PUBLIC]
Body: { "email", "password" }
Response 200: { "data": { "accessToken", "expiresIn": 900 } }
Set-Cookie: refreshToken=...; HttpOnly; SameSite=Strict
```

### Refresh Token
```
POST /api/v1/auth/refresh  [PUBLIC — uses httpOnly cookie]
Response 200: { "data": { "accessToken", "expiresIn": 900 } }
```

### Logout
```
POST /api/v1/auth/logout
Response 204 No Content
```

---

## HTTP Status Code Reference

| Code | When to Use |
|---|---|
| 200 OK | Successful GET, PATCH, DELETE |
| 201 Created | Successful synchronous POST (resource created) |
| 202 Accepted | Async operation initiated (saga started) |
| 204 No Content | Successful DELETE with no body |
| 400 Bad Request | Validation error |
| 401 Unauthorized | Missing or invalid JWT |
| 403 Forbidden | Valid JWT but insufficient role |
| 404 Not Found | Resource doesn't exist |
| 409 Conflict | Business rule violation (invalid state transition) |
| 422 Unprocessable Entity | Semantic error (e.g., SKU doesn't exist) |
| 429 Too Many Requests | Rate limit exceeded |
| 500 Internal Server Error | Unexpected server error |
| 503 Service Unavailable | Downstream dependency unavailable |
