# 09 — WebSocket Real-Time System

## Purpose

Customers and ops admins need live updates without polling. When an order status changes, the UI updates immediately — no refresh required.

Use cases:
- Customer sees order go from "Confirmed" → "Shipped" in real time
- Admin sees new orders appear in the live feed
- Ops dashboard KPIs (order count, revenue) tick up as orders come in
- Low-stock alerts appear instantly on the inventory page

---

## Technology

**STOMP over WebSocket** via Spring WebSocket.

STOMP (Simple Text Oriented Messaging Protocol) adds a message frame structure on top of raw WebSocket, making it easy to:
- Subscribe to named destinations (like pub/sub topics)
- Send messages to specific users
- Integrate with Spring's messaging abstractions

Client library: `@stomp/stompjs` + `sockjs-client` (SockJS fallback for environments that don't support raw WebSocket).

---

## Connection Lifecycle

### Connect
1. Client opens WebSocket to `/ws` endpoint
2. Sends STOMP `CONNECT` frame with `Authorization: Bearer <token>` header
3. Server validates JWT in the `ChannelInterceptor`
4. On success: STOMP session established, `userId` stored in session attributes

### Subscribe
After connecting, client sends STOMP `SUBSCRIBE` frames:

| Subscriber | Destination | What they receive |
|---|---|---|
| Customer | `/user/queue/orders` | Status updates for their own orders |
| Admin | `/topic/orders.feed` | All new orders and status changes |
| Admin | `/topic/inventory.alerts` | Low-stock alerts |

The `/user/queue/...` prefix is special — Spring automatically routes to the specific user's session, so customer A doesn't see customer B's updates.

### Disconnect
Client sends STOMP `DISCONNECT` or closes the browser tab. Server cleans up session state.

---

## Message Flow

### Order Status Change

```
[Order Service]
  - Status changes in DB
  - Publishes Kafka event (existing flow)
        │
        ▼
[WebSocket Notification Consumer] (within Order Service or separate)
  - Listens to Kafka orders topic
  - Determines which user to notify
  - Calls SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/orders", payload)
```

### New Order for Admin Feed

```
[Order Service]
  - OrderCreated event published to Kafka
        │
[Admin Feed Consumer]
  - Listens to Kafka
  - Calls SimpMessagingTemplate.convertAndSend("/topic/orders.feed", orderSummary)
  - All admin subscribers receive it
```

---

## WebSocket Message Format

```json
{
  "type": "ORDER_STATUS_CHANGED",
  "orderId": "uuid",
  "oldStatus": "CONFIRMED",
  "newStatus": "SHIPPED",
  "trackingNumber": "1Z999AA10123456784",
  "carrier": "UPS",
  "updatedAt": "2026-06-04T14:30:00Z"
}
```

```json
{
  "type": "ORDER_CREATED",
  "orderId": "uuid",
  "customerId": "uuid",
  "totalAmount": 149.99,
  "itemCount": 3,
  "createdAt": "2026-06-04T14:30:00Z"
}
```

```json
{
  "type": "LOW_STOCK_ALERT",
  "sku": "WIDGET-XL-RED",
  "warehouseId": "WH-01",
  "quantityAvailable": 5,
  "threshold": 10
}
```

---

## Client-Side Integration (React)

### Hook: `useWebSocket`
- Creates a single `Client` (STOMP) instance per user session
- Connects on mount, disconnects on unmount
- Exposed via React Context so all components share one connection
- On receiving a message → dispatches to relevant React Query cache update

### Cache Update on WebSocket Message
```
Receive ORDER_STATUS_CHANGED for orderId
  → queryClient.setQueryData(['order', orderId], updater)
  → updates status badge immediately — no HTTP round trip
```

For the admin feed:
```
Receive ORDER_CREATED
  → queryClient.invalidateQueries(['orders'])
  → triggers background refetch of order list
```

---

## Scalability: Multi-Instance Challenge

With multiple backend instances, a WebSocket connection goes to one specific instance. But a Kafka event may be processed by a different instance.

### Solution: Message Broker Relay

Use a **message broker** (RabbitMQ or Redis Pub/Sub) as a relay between instances:

```
Instance A processes Kafka event
  → publishes to Redis pub/sub channel "ws:user:{userId}"
All instances subscribe to Redis
  → Instance B (which holds the user's WSocket connection) receives it
  → Forwards to user's WebSocket session
```

Spring WebSocket supports this natively with the STOMP broker relay (using RabbitMQ or ActiveMQ).

---

## Authentication

WebSocket connections are authenticated once at connect time:

1. JWT passed in STOMP CONNECT headers
2. `ChannelInterceptor.preSend()` intercepts CONNECT frames
3. Validates JWT → extracts `userId` and `roles`
4. Stores principal in STOMP session
5. All subsequent subscription/message frames inherit the authenticated principal

Subscriptions are also validated — customers can only subscribe to `/user/queue/orders` (routed to their own session). Attempts to subscribe to `/topic/orders.feed` without `ROLE_ADMIN` are rejected.

---

## Heartbeat and Reconnect

- STOMP heartbeat: send every 10s, expect every 30s
- If connection drops: client auto-reconnects with exponential backoff (1s, 2s, 4s, max 30s)
- After reconnect: re-subscribe to all previous destinations
- Missed messages during disconnect: client refetches current state via HTTP after reconnect
