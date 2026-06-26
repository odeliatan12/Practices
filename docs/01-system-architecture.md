# 01 — System Architecture

## Architecture Style

**Microservices with Event-Driven Communication**

Services communicate in two ways:
- **Synchronous** (REST / gRPC): for queries and immediate responses (e.g., "fetch order by ID")
- **Asynchronous** (Kafka): for state changes that fan out to multiple consumers (e.g., "order confirmed" triggers inventory reservation, payment capture, notification)

---

## Service Map

```
                        ┌─────────────────┐
  Browser / Mobile ────▶│   API Gateway   │
                        │ (Spring Cloud)  │
                        └────────┬────────┘
                                 │ routes by path
          ┌──────────────────────┼──────────────────────┐
          │                      │                       │
          ▼                      ▼                       ▼
  ┌───────────────┐    ┌──────────────────┐   ┌─────────────────┐
  │  Order        │    │  Inventory       │   │  User / Auth    │
  │  Service      │    │  Service         │   │  Service        │
  └───────┬───────┘    └────────┬─────────┘   └─────────────────┘
          │                     │
          │  Kafka events        │  Kafka events
          ▼                     ▼
  ┌───────────────┐    ┌──────────────────┐   ┌─────────────────┐
  │  Payment      │    │  Fulfillment     │   │  Notification   │
  │  Service      │    │  Service         │   │  Service        │
  └───────────────┘    └──────────────────┘   └─────────────────┘
          │                     │
          ▼                     ▼
  ┌─────────────────────────────────────────────────────────────┐
  │                     Apache Kafka                            │
  │  topics: orders, payments, inventory, shipments, notifs     │
  └─────────────────────────────────────────────────────────────┘

  ┌─────────────┐   ┌─────────────┐   ┌───────────────────┐
  │   Redis     │   │ PostgreSQL  │   │  Elasticsearch    │
  │   Cache     │   │  (per svc)  │   │  (order search)   │
  └─────────────┘   └─────────────┘   └───────────────────┘
```

---

## Services and Responsibilities

### API Gateway
- Single entry point for all client traffic
- Routes requests to downstream services by path prefix
- Enforces JWT authentication before forwarding
- Rate limiting, request logging, CORS

### Order Service
- Creates and manages the order aggregate
- Owns the order state machine
- Publishes `OrderCreated`, `OrderCancelled`, `OrderStatusChanged` events
- Exposes REST API for CRUD and status queries

### Inventory Service
- Tracks stock levels per SKU per warehouse
- Listens for `OrderCreated` → reserves stock
- Listens for `OrderCancelled` / `PaymentFailed` → releases reservation
- Publishes `StockReserved`, `StockReleased`, `LowStockAlert`

### Payment Service
- Integrates with Stripe (or stub)
- Listens for `StockReserved` → initiates payment
- Publishes `PaymentCaptured`, `PaymentFailed`
- Handles refunds on return flow

### Fulfillment Service
- Listens for `PaymentCaptured` → creates fulfillment job
- Simulates or integrates with WMS
- Publishes `OrderShipped`, `OrderDelivered`
- Tracks carrier shipment events

### Notification Service
- Listens to all status-change events
- Sends email (SendGrid) and SMS (Twilio) or stubs
- Idempotent: deduplicates on event ID

### User / Auth Service
- Manages customer and admin accounts
- Issues and validates JWTs
- OAuth2 login support (Google)

---

## Communication Patterns

### Synchronous (REST)
Used when the caller needs an immediate response:
- `GET /orders/{id}` — read a single order
- `POST /orders` — create order (kicks off async saga)
- `GET /inventory/sku/{sku}` — check stock level

### Asynchronous (Kafka)
Used when an action fans out to multiple consumers or is fire-and-forget:
- Order confirmed → inventory, payment, notification all react independently
- Enables loose coupling — services don't know about each other

### Saga Pattern
The order creation flow is a **choreography-based saga**: each service listens for the previous step's success/failure event and publishes its own result. No central orchestrator.

---

## Data Isolation

Each service has its **own database schema** (or separate DB instance in production). Services never share a database or call each other's DB directly.

| Service | Database |
|---|---|
| Order | `orders_db` (PostgreSQL) |
| Inventory | `inventory_db` (PostgreSQL) |
| Payment | `payments_db` (PostgreSQL) |
| Fulfillment | `fulfillment_db` (PostgreSQL) |
| User | `users_db` (PostgreSQL) |
| Notification | log in Kafka / Redis |

Cross-service queries that need joined data go through the **API Gateway** or a dedicated **BFF (Backend for Frontend)** layer that calls multiple services and composes the response.

---

## Deployment Topology (Target)

- Each service runs as a Docker container
- Orchestrated by Kubernetes (local: Minikube or kind)
- Kafka and Redis run as stateful sets
- PostgreSQL runs per-service or as logical schemas on one host (dev)
- Ingress controller routes external traffic to the gateway
