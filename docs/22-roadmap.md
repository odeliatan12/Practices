# 22 — Roadmap

## How to Use This Document

This roadmap is structured in phases — start from Phase 1 and work forward. Each phase builds on the previous. Within a phase, tackle items in any order unless a dependency is noted.

The goal is to have a working, incrementally deployable system at every phase.

---

## Phase 1 — Foundation (Weeks 1–2)

Get a single service working end-to-end before adding complexity.

**Backend**
- [ ] Set up Maven multi-module project structure
- [ ] Create `shared-lib` module with `DomainEvent` envelope POJO
- [ ] Implement `order-service`: domain model, JPA entities, Flyway migrations
- [ ] Implement `POST /orders` and `GET /orders/{id}` REST endpoints
- [ ] Add bean validation on all request DTOs
- [ ] Implement `@ControllerAdvice` with RFC 7807 error responses
- [ ] Write unit tests for Order domain state machine (all transitions)
- [ ] Write integration test for `POST /orders` with Testcontainers PostgreSQL

**Infrastructure**
- [ ] Write `docker-compose.yml` with PostgreSQL, Kafka, Redis
- [ ] Add health check endpoints (`/actuator/health`)
- [ ] Add Dockerfile for `order-service`

**Milestone**: Can create and retrieve an order via REST. Tests pass. Docker Compose brings up infrastructure.

---

## Phase 2 — Event-Driven Core (Weeks 3–4)

Add Kafka and the saga between Order and Inventory.

**Backend**
- [ ] Add Kafka producer to `order-service` (outbox pattern)
- [ ] Implement `inventory-service` with `products`, `stock_levels` tables
- [ ] Implement Kafka consumer in `inventory-service` for `OrderCreated`
- [ ] Implement stock reservation logic
- [ ] Publish `StockReserved` / `StockUnavailable` from inventory service
- [ ] Order service listens to inventory events and updates order status
- [ ] Implement consumer idempotency with Redis
- [ ] Add Dead Letter Queue handling for failed messages
- [ ] Integration test: full saga (order created → stock reserved → order confirmed)

**Milestone**: Order creation triggers the full inventory saga. Orders transition to CONFIRMED or CANCELLED automatically.

---

## Phase 3 — Payment & Fulfillment (Weeks 5–6)

Complete the happy path saga.

**Backend**
- [ ] Implement `payment-service` with Stripe stub adapter
- [ ] Payment service consumes `StockReserved`, publishes `PaymentCaptured` / `PaymentFailed`
- [ ] Order service handles `PaymentFailed` → PAYMENT_FAILED status + compensating saga
- [ ] Implement `fulfillment-service` — consumes `PaymentCaptured`, simulates warehouse
- [ ] Fulfillment publishes `OrderShipped` and `OrderDelivered`
- [ ] Implement return/refund flow endpoints and saga

**Milestone**: Full happy path works. Order goes PENDING → CONFIRMED → SHIPPED → DELIVERED via events. Failure paths (out of stock, payment failed) also handled.

---

## Phase 4 — Auth & User Service (Week 7)

Secure the system.

**Backend**
- [ ] Implement `user-service` with JWT issuance (RS256)
- [ ] Add refresh token rotation
- [ ] Configure `api-gateway` (Spring Cloud Gateway) with JWT validation filter
- [ ] Add `@PreAuthorize` role checks to all endpoints
- [ ] Enforce customer can only see own orders
- [ ] Rate limiting on login and order creation endpoints
- [ ] Write integration tests for auth flow

**Milestone**: All endpoints secured. Unauthenticated requests rejected. Role-based access enforced.

---

## Phase 5 — Frontend (Weeks 8–9)

Build the UI.

**Frontend**
- [ ] Set up React + TypeScript + Vite + Tailwind + shadcn/ui
- [ ] Login page and auth flow (JWT + refresh)
- [ ] Axios instance with interceptors (attach token, refresh on 401)
- [ ] Order list page with React Query + pagination
- [ ] Order detail page with status timeline
- [ ] Place order form (multi-step) with Zod validation
- [ ] Admin dashboard with KPI cards
- [ ] Admin: inventory table with stock adjustment

**Milestone**: Full customer journey works in browser. Can place an order and see it move through statuses.

---

## Phase 6 — Real-Time & Search (Week 10)

Add live updates and search.

**Backend**
- [ ] Configure Spring WebSocket with STOMP
- [ ] WebSocket auth via `ChannelInterceptor`
- [ ] Publish order status changes to WebSocket after each Kafka event
- [ ] Implement `search-indexer` service (Kafka → Elasticsearch)
- [ ] Implement `GET /orders/search` endpoint with full-text + faceted filters
- [ ] Add order analytics aggregations endpoint

**Frontend**
- [ ] `useWebSocket` hook with auto-reconnect
- [ ] Order status badge updates in real time on order detail page
- [ ] Admin live order feed
- [ ] Search bar on orders list page

**Milestone**: Order detail page updates live. Search works with text and filters.

---

## Phase 7 — Notifications & File Upload (Week 11)

**Backend**
- [ ] Implement `notification-service` with email/SMS stubs
- [ ] Wire up all event → notification mappings
- [ ] Notification deduplication and log
- [ ] File upload: presigned URL endpoint
- [ ] Bulk CSV import for orders and inventory
- [ ] CSV validation and error report

**Milestone**: Customers receive (stubbed) notifications at each order step. Admins can bulk import orders.

---

## Phase 8 — Production Hardening (Week 12)

**Backend**
- [ ] Add Resilience4j circuit breakers to all outbound HTTP calls
- [ ] Add timeouts to all DB, Redis, Kafka operations
- [ ] Implement graceful shutdown
- [ ] Add structured JSON logging with trace context
- [ ] Add custom business metrics with Micrometer
- [ ] Write Prometheus alert rules for key SLIs
- [ ] Performance test with Gatling — verify p95 latency targets

**Infrastructure**
- [ ] Write GitHub Actions CI pipeline (test + build + push)
- [ ] Write Kubernetes deployment manifests for all services
- [ ] Add Prometheus + Grafana to Docker Compose and K8s
- [ ] Add Loki log aggregation

**Milestone**: System is observable, resilient, and deployable to Kubernetes with zero-downtime rolling updates.

---

## Stretch Goals (After Phase 8)

- Replace Stripe stub with real Stripe integration
- OAuth2 login (Google)
- Mobile push notifications (Firebase)
- Multi-currency and international shipping
- Migrate from choreography saga to Saga Orchestrator pattern
- Introduce gRPC for internal high-frequency service calls
- Multi-tenant: support multiple merchants
