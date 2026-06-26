# 19 — Testing Strategy

## Testing Pyramid

```
            ╱╲
           ╱ E2E╲          Few — slow, expensive, cover critical user journeys
          ╱──────╲
         ╱Integr- ╲        More — test service behavior with real DB/Kafka
        ╱  ation   ╲
       ╱────────────╲
      ╱     Unit     ╲     Many — fast, test business logic in isolation
     ╱────────────────╲
```

---

## Unit Tests

Test individual classes in isolation. No Spring context loaded — pure Java.

**What to unit test:**
- Domain objects: `Order.transitionTo(newStatus)` throws when transition is invalid
- Service layer: business rules, calculation logic, state machine enforcement
- Mappers: DTO ↔ domain conversion correctness
- Validators: custom Bean Validation constraints

**Tools:**
- JUnit 5
- Mockito (mock repositories, Kafka producers, external clients)
- AssertJ for fluent assertions

**Naming convention:**
```
OrderTest.java                    ← tests for Order domain class
OrderServiceTest.java             ← tests for OrderService with mocked repo
LineItemCalculationTest.java      ← focused on subtotal/total calculations
```

**Example structure:**
```java
// given
// when
// then
```

Each test: one logical assertion. No shared mutable state between tests.

---

## Integration Tests

Test a slice of the application with real dependencies (DB, Kafka, Redis).

**What to integration test:**
- REST endpoints: full HTTP request → DB → response (no mocks)
- Kafka consumers: publish event → verify DB state changed
- Repository layer: complex queries, pagination, index effectiveness
- Cache behavior: verify cache hits/misses

**Tools:**
- `@SpringBootTest` (full context) or `@WebMvcTest` (controller slice)
- **Testcontainers**: spins up real PostgreSQL, Kafka, Redis in Docker for each test run
- `MockMvc` for HTTP layer tests

**Example: Order Creation Integration Test**
1. POST /api/v1/orders with valid body
2. Assert HTTP 202 response
3. Assert order row exists in PostgreSQL
4. Assert outbox row exists with correct event type
5. Consume from Kafka (test consumer) and assert `OrderCreated` event received

**Test database:**
- Each test class gets a fresh schema via `@Sql` scripts or Flyway clean+migrate
- Tests run in parallel within a class using `@DirtiesContext` carefully
- Testcontainers reuses the same container across test classes (singleton pattern)

---

## Kafka Saga Integration Tests

Test the full choreography-based saga end-to-end within the test environment.

**Approach:**
- Run all services in-process (embedded) or use Testcontainers for each
- Publish `OrderCreated` event manually
- Use `KafkaTestUtils.getRecords()` to assert that `StockReserved` was published
- Assert DB state of each service after saga completes

**Tools:**
- Spring Kafka Test (`EmbeddedKafkaBroker`)
- Testcontainers Kafka module

---

## Contract Tests

Ensure services agree on event schemas without deploying all services together.

**Using Pact or Spring Cloud Contract:**
- Inventory Service defines: "I expect `OrderCreated` events to have these fields"
- Order Service publishes a real event and verifies it matches the contract
- Contract verified in CI before merge

This catches schema breaking changes early.

---

## End-to-End Tests

Minimal set covering the most critical user journeys. Run against a staging environment.

**Journeys to cover:**
1. Register → Login → Place order → Verify order appears in order list
2. Place order → Payment captured → Status shows CONFIRMED
3. Cancel an order in PENDING state → Verify CANCELLED
4. Admin: view dashboard → see live order in feed

**Tools:**
- Playwright (browser automation)
- Tests run after staging deployment in CI

---

## Test Data Management

**Unit / Integration tests:**
- Use builder pattern or test fixtures for domain objects:
  ```java
  Order order = OrderTestFixture.aConfirmedOrder()
      .withCustomerId("customer-1")
      .build();
  ```

**Do NOT use production data** in tests — use generated, anonymized data only.

---

## Code Coverage

Target: **80% line coverage** overall, **90%** on domain and service layers.

Coverage is a floor, not a goal — 80% with meaningful tests beats 100% with trivial ones.

Use JaCoCo. Fail the build if coverage drops below threshold:
```xml
<limit>
  <element>CLASS</element>
  <value>LINE</value>
  <minimum>0.80</minimum>
</limit>
```

---

## Test Environment Parity

Testcontainers images must match production versions:
- `postgres:16-alpine` (same as RDS version)
- `confluentinc/cp-kafka:7.6.0` (same as MSK version)
- `redis:7.2-alpine`
- `elasticsearch:8.13.0`

Version mismatch between test and prod is a common source of "works in tests, breaks in prod."

---

## Performance / Load Tests

See [doc 18](18-performance-optimization.md) for load test scenarios with Gatling/k6.

Run in CI on a schedule (nightly) — not on every PR (too slow and expensive).
