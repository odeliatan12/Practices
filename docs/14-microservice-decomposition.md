# 14 — Microservice Decomposition

## Decomposition Principles

Services are split along **bounded contexts** — each service is responsible for one cohesive domain. The boundary is defined by:

- What data it owns (has its own DB schema)
- What business capability it encapsulates
- What team would own it in a real org

---

## Service Inventory

| Service | Port | Domain | Owns |
|---|---|---|---|
| api-gateway | 8080 | Routing & security | Nothing |
| order-service | 8081 | Order lifecycle | `orders`, `line_items`, `order_events` |
| inventory-service | 8082 | Stock management | `products`, `stock_levels`, `stock_reservations` |
| payment-service | 8083 | Payment capture & refunds | `payments`, `refunds` |
| fulfillment-service | 8084 | Warehouse & shipping | `fulfillment_jobs`, `shipments`, `tracking_events` |
| notification-service | 8085 | Customer & ops messaging | `notification_log` |
| user-service | 8086 | Identity & authentication | `users`, `roles`, `refresh_tokens` |
| search-indexer | 8087 | Elasticsearch sync | None (read from Kafka, write to ES) |

---

## Service Boundaries in Detail

### Why Order Service ≠ Inventory Service

Order Service knows about **customer intent** (I want to buy X).  
Inventory Service knows about **physical stock** (we have Y units of X).

Merging them would create a god service. Splitting them lets:
- Inventory be reused by a purchasing team (restock orders) independently
- Order to exist even if inventory is temporarily unavailable (degraded mode)
- Each to scale independently (inventory reads are far more frequent than order writes)

### Why Notification Service is Separate

Notifications are a cross-cutting concern — they react to events from every other service. If they were embedded in Order or Payment Service, each service would need Twilio/SendGrid SDK, template logic, and dedup logic. Extracting it means:
- One place to manage all templates
- One place to manage opt-out preferences
- Easy to add new channels (push notifications) without touching other services

### Why Search Indexer is Separate

Elasticsearch writes are eventually consistent and can be slower. If embedded in Order Service, a slow ES write could block the API response or require careful async handling. As a separate consumer, it:
- Processes at its own pace
- Can be restarted independently without affecting order processing
- Can rebuild the index by replaying Kafka from offset 0

---

## Inter-Service Communication

### Synchronous (REST)
Used sparingly — only when you need an immediate answer before continuing:

| Caller | Called | Endpoint | Why sync |
|---|---|---|---|
| API Gateway | User Service | `GET /internal/users/{id}/validate` | JWT auth on every request |
| Order Service | User Service | `GET /internal/users/{id}` | Validate customer exists at order create |
| Notification Service | User Service | `GET /internal/users/{id}/preferences` | Check opt-in before sending |

All internal REST calls use `/internal/` prefix — blocked at the API gateway, accessible only service-to-service.

### Asynchronous (Kafka)
Used for state changes that fan out. See [doc 07](07-kafka-event-driven-design.md) for full event catalog.

---

## Service Template

Every new service follows this template to ensure consistency:

```
{service-name}/
├── Dockerfile
├── pom.xml
└── src/main/
    ├── java/com/oms/{service}/
    │   ├── {Service}Application.java     ← @SpringBootApplication
    │   ├── controller/
    │   ├── service/
    │   ├── domain/
    │   ├── repository/
    │   ├── event/
    │   │   ├── consumer/
    │   │   └── producer/
    │   ├── dto/
    │   ├── mapper/
    │   ├── exception/
    │   └── config/
    │       ├── KafkaConfig.java
    │       ├── RedisConfig.java
    │       └── SecurityConfig.java
    └── resources/
        ├── application.yml
        ├── application-dev.yml
        └── db/migration/
```

---

## Shared Library (`shared-lib`)

A Maven module consumed by all services. Contains:

- Event envelope POJO (`DomainEvent<T>`)
- Common DTOs shared across services (e.g., `AddressDTO`)
- `IdempotencyChecker` utility (Redis-based)
- `ProblemDetailException` base class
- Common `RequestLoggingFilter`

Rules:
- Shared lib contains NO business logic — only data structures and utilities
- Changes to shared lib are backward compatible (new optional fields only)
- Never add service-specific logic to shared lib

---

## Failure Isolation

Each service is isolated so one failure doesn't cascade:

| Scenario | Behavior |
|---|---|
| Notification Service down | Orders still process normally; notifications caught up when service recovers (Kafka offset retained) |
| Elasticsearch down | Search returns error; order creation and status changes unaffected |
| Payment Service slow | Circuit breaker opens after timeout threshold; upstream returns 503 gracefully |
| Redis unavailable | Cache miss → fall through to DB; idempotency check skipped (log warning) |

---

## Service Health Contracts

Each service must expose:

```
GET /actuator/health      → { status: UP | DOWN | DEGRADED }
GET /actuator/health/liveness   → Kubernetes liveness probe
GET /actuator/health/readiness  → Kubernetes readiness probe (checks DB, Kafka, Redis)
GET /actuator/info        → { version, build.time, git.commit }
GET /actuator/metrics     → Prometheus metrics endpoint
```

Readiness probe returns `DOWN` if DB or Kafka connection is unavailable — Kubernetes stops routing traffic until it recovers.
