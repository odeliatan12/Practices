# 02 — Backend Architecture

## Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.x
- **Build tool**: Maven (multi-module project)
- **Database access**: Spring Data JPA + Hibernate
- **Messaging**: Spring Kafka
- **Cache**: Spring Data Redis
- **Security**: Spring Security + JWT

---

## Project Structure

```
order-management-system/
├── api-gateway/                  ← Spring Cloud Gateway
├── order-service/
│   ├── src/main/java/com/oms/order/
│   │   ├── controller/           ← REST controllers
│   │   ├── service/              ← Business logic
│   │   ├── domain/               ← Entities and value objects
│   │   │   ├── Order.java
│   │   │   ├── LineItem.java
│   │   │   └── OrderStatus.java  ← Enum state machine
│   │   ├── repository/           ← JPA repositories
│   │   ├── event/                ← Kafka event POJOs
│   │   ├── dto/                  ← Request/response DTOs
│   │   ├── mapper/               ← Domain ↔ DTO mapping
│   │   ├── exception/            ← Custom exceptions + handlers
│   │   └── config/               ← Kafka, Redis, Security config
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/         ← Flyway SQL migrations
├── inventory-service/
├── payment-service/
├── fulfillment-service/
├── notification-service/
├── user-service/
└── shared-lib/                   ← Shared event schemas, DTOs, utils
```

---

## Layered Architecture (per service)

```
HTTP Request
     │
     ▼
┌─────────────┐
│  Controller │  ← validates input, delegates to service, maps response
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Service   │  ← orchestrates use cases, owns transaction boundaries
└──────┬──────┘
       │
  ┌────┴────┐
  │         │
  ▼         ▼
┌──────┐  ┌──────────────┐
│ Repo │  │ Kafka / Redis│  ← infrastructure adapters
└──────┘  └──────────────┘
  │
  ▼
┌──────────────┐
│  PostgreSQL  │
└──────────────┘
```

---

## Domain Model — Order Service

### Order (Aggregate Root)
- `id` (UUID)
- `customerId` (UUID)
- `status` (OrderStatus enum)
- `lineItems` (List\<LineItem\>)
- `shippingAddress` (embedded value object)
- `totalAmount` (BigDecimal)
- `createdAt`, `updatedAt`
- `version` (optimistic locking)

### LineItem (Entity)
- `id` (UUID)
- `orderId` (FK)
- `sku` (String)
- `productName` (String)
- `quantity` (int)
- `unitPrice` (BigDecimal)
- `subtotal` (BigDecimal — derived)

### OrderStatus (Enum / State Machine)
```
PENDING
PAYMENT_PROCESSING
PAYMENT_FAILED
CONFIRMED
PROCESSING
SHIPPED
DELIVERED
CANCELLED
RETURN_REQUESTED
RETURN_PROCESSING
REFUNDED
```

Valid transitions are enforced in the `Order` domain object — invalid transitions throw a `IllegalOrderStateException`.

---

## API Design Principles

- RESTful resources with plural nouns (`/orders`, `/line-items`)
- `POST` to create, `PATCH` to update status, `GET` to read
- All responses wrapped in a standard envelope:

```json
{
  "data": { ... },
  "meta": {
    "timestamp": "2026-06-04T10:00:00Z",
    "requestId": "abc-123"
  }
}
```

- Error responses use RFC 7807 Problem Details format
- Paginated list endpoints use cursor-based pagination

---

## Event Publishing

Every state transition publishes a Kafka event. The service:
1. Updates the DB row inside a transaction
2. Writes the event to an **outbox table** (same transaction)
3. A background poller (or Debezium CDC) reads the outbox and publishes to Kafka

This is the **Transactional Outbox Pattern** — guarantees at-least-once delivery without two-phase commit.

---

## Idempotency

- Kafka consumers use `event.id` to deduplicate (stored in Redis with TTL)
- REST POST endpoints accept an `Idempotency-Key` header; result is cached for 24h
- Database upserts use `ON CONFLICT DO NOTHING` where appropriate

---

## Error Handling

- `@ControllerAdvice` with `@ExceptionHandler` maps domain exceptions to HTTP status codes
- Kafka consumer errors go to a **Dead Letter Topic (DLT)** after N retries
- Circuit breakers (Resilience4j) on outbound HTTP calls to payment / shipping

---

## Validation

- Bean Validation (`@NotNull`, `@Min`, `@Size`) on all DTOs
- Custom validators for SKU format, address fields
- Validation errors return 400 with field-level error messages

---

## Configuration Management

- `application.yml` for defaults
- Environment-specific profiles: `dev`, `staging`, `prod`
- Secrets (DB password, Stripe key) from environment variables — never hardcoded
- Spring Cloud Config Server for centralized config (optional)
