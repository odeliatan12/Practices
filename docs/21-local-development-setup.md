# 21 — Local Development Setup

## Prerequisites

Install these tools before starting:

| Tool | Version | Purpose |
|---|---|---|
| JDK | 21+ | Run Spring Boot services |
| Maven | 3.9+ | Build Java projects |
| Node.js | 20+ | Run React frontend |
| Docker Desktop | Latest | Run infrastructure locally |
| Docker Compose | V2 | Orchestrate local containers |
| IntelliJ IDEA (or VS Code) | Latest | IDE |
| Postman (or HTTPie) | — | API testing |
| git | Latest | Version control |

Optional but recommended:
- `k9s` — terminal UI for Kubernetes (if you run local k8s)
- `kafkacat` / `kcat` — CLI for Kafka inspection
- `redis-cli` — inspect Redis state
- `psql` — inspect PostgreSQL

---

## Repository Structure

```
order-management-system/          ← root directory
├── docker-compose.yml            ← start all infrastructure
├── docker-compose.services.yml   ← start all microservices
├── .env.example                  ← copy to .env and fill in values
├── shared-lib/                   ← shared Maven module
├── api-gateway/
├── order-service/
├── inventory-service/
├── payment-service/
├── fulfillment-service/
├── notification-service/
├── user-service/
├── search-indexer/
└── frontend/
```

---

## First-Time Setup

```bash
# 1. Clone the repository
git clone https://github.com/your-org/order-management-system.git
cd order-management-system

# 2. Copy environment file
cp .env.example .env
# Edit .env to fill in any required local values

# 3. Start infrastructure (PostgreSQL, Kafka, Redis, Elasticsearch, MinIO)
docker compose up -d

# 4. Wait for infrastructure to be healthy (about 30 seconds)
docker compose ps    # all should show "healthy"

# 5. Install shared library to local Maven repo
cd shared-lib && mvn install -q && cd ..

# 6. Build all services
mvn install -DskipTests -q

# 7. Start a service (choose one to start with)
cd order-service && mvn spring-boot:run

# 8. Start the frontend
cd frontend && npm install && npm run dev
```

Frontend runs at: `http://localhost:5173`  
API Gateway runs at: `http://localhost:8080`

---

## Running Individual Services

Each service can be started independently from its directory:

```bash
cd order-service
mvn spring-boot:run

# With a specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev,stub-payments
```

Or from IntelliJ IDEA: open the service's `Application.java` → Run.

---

## Environment Variables (`.env`)

```bash
# Database
POSTGRES_USER=oms
POSTGRES_PASSWORD=oms_dev_password

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Elasticsearch
ELASTICSEARCH_HOST=localhost:9200

# MinIO (local S3)
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin

# Auth
JWT_PRIVATE_KEY_PATH=./dev-certs/jwt-private.pem
JWT_PUBLIC_KEY_PATH=./dev-certs/jwt-public.pem

# Stub mode (disable real Stripe/Twilio/SendGrid in dev)
PAYMENT_MODE=stub
NOTIFICATION_MODE=stub
SHIPPING_MODE=stub
```

---

## Infrastructure Details

### PostgreSQL
- Port: `5432`
- Each service gets its own database created automatically at startup
- Connect: `psql -h localhost -U oms -d orders_db`
- Flyway migrations run automatically on service startup

### Kafka
- Port: `9092` (broker)
- Runs in KRaft mode (no ZooKeeper dependency)
- Topics are auto-created with default settings in dev
- Inspect topics: `kcat -b localhost:9092 -L`
- Consume from a topic: `kcat -b localhost:9092 -t orders -C`

### Redis
- Port: `6379`
- Inspect: `redis-cli -h localhost ping`
- View keys: `redis-cli -h localhost KEYS "order:*"`

### Elasticsearch
- Port: `9200` (HTTP), `9300` (transport)
- Dev UI: `http://localhost:9200` or use Kibana at `http://localhost:5601`
- Security disabled in dev

### MinIO (S3)
- API Port: `9000`
- Console: `http://localhost:9001` (user: `minioadmin`, pass: `minioadmin`)

---

## Running Tests

```bash
# Unit tests (fast, no Docker needed)
mvn test

# Integration tests (requires Docker Desktop running)
mvn verify -P integration-tests

# All tests for a specific service
cd order-service && mvn verify

# Frontend tests
cd frontend && npm test
```

---

## Hot Reload

**Backend**: Spring Boot DevTools enabled in dev profile — detects classpath changes and restarts the application context automatically. IntelliJ "Build Project" (Ctrl+F9 / Cmd+F9) triggers a reload.

**Frontend**: Vite HMR (Hot Module Replacement) — browser updates instantly on file save.

---

## Seeding Development Data

A `DataSeeder` component runs on startup in `dev` profile and inserts:
- 3 test users (customer, admin, warehouse roles)
- 20 sample products with stock
- 5 sample orders in various statuses

Run a reset:
```bash
# Wipe and reseed (dev only)
curl -X POST http://localhost:8080/dev/reset
```

This endpoint is blocked in staging and production profiles.

---

## Useful Dev Commands

```bash
# View logs for a service
docker compose logs -f order-service

# Restart a service container
docker compose restart kafka

# Wipe all data and start fresh
docker compose down -v && docker compose up -d

# Access PostgreSQL shell
docker compose exec postgres psql -U oms -d orders_db

# List Kafka consumer group lag
docker compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --all-groups
```
