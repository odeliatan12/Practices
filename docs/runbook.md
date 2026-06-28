# OMS Runbook — Start, Stop, and Deploy

## Services at a glance

| Service              | Port (local) | Port (prod) | Has DB?       |
|----------------------|-------------|-------------|---------------|
| api-gateway          | 8090        | 80          | No            |
| order-service        | 8081        | 8080        | orders_db     |
| payment-service      | 8083        | 8083        | payments_db   |
| inventory-service    | 8082        | 8082        | inventory_db  |
| notification-service | 8086        | 8086        | No            |
| Adminer (DB UI)      | 8085        | 8085        | —             |
| PostgreSQL           | 5433        | internal    | —             |
| Kafka                | 9092        | internal    | —             |
| Redis                | 6380        | internal    | —             |

---

## Local development

### Start everything

```bash
# Start infrastructure (postgres, kafka, redis, elasticsearch, minio, adminer)
docker compose up -d

# Start application services (builds images if they don't exist)
docker compose up -d payment-service inventory-service notification-service
```

### Stop everything

```bash
# Stop all containers but keep data volumes
docker compose down

# Stop and delete all data (full reset)
docker compose down -v
```

### Rebuild a single service after code changes

```bash
# 1. Rebuild the image
docker build -f payment-service/Dockerfile -t oms-payment-service .

# 2. Recreate the container with the new image
docker compose up -d payment-service
```

Replace `payment-service` with `inventory-service` or `notification-service` as needed.

### View logs

```bash
# Follow logs for one service
docker logs -f oms-payment-service

# Follow logs for multiple services
docker compose logs -f payment-service inventory-service notification-service
```

### Check container status

```bash
docker compose ps
```

---

## Deploy to production (via Jenkins)

### Normal flow — push to main

1. Commit and push your changes to the `main` branch
2. Jenkins automatically:
   - Runs tests for all modules
   - Builds fat JARs
   - Builds Docker images for all 5 services (parallel)
   - Pushes images to GitHub Container Registry (`ghcr.io/odeliatan12/oms/`)
   - SSHes into the production server and runs `docker compose up -d` for all services

### Trigger a build manually

1. Open Jenkins → OMS pipeline → **Build Now**

### Monitor a running build

1. Open Jenkins → click the build number → **Console Output**
2. Watch for `BUILD SUCCESS` or `BUILD FAILURE`

### What gets deployed

Jenkins Deploy stage runs only on the `main` branch. It deploys:
- `order-service`
- `api-gateway`
- `payment-service`
- `inventory-service`
- `notification-service`
- `adminer`

Infrastructure (postgres, kafka, redis) runs permanently on the server and is **not** restarted by Jenkins.

---

## Production — manual operations

### SSH into the production server

```bash
# Get the host from Jenkins credentials, or ask the team
ssh <deploy-user>@<deploy-host>
```

### Check running containers on prod

```bash
docker ps
```

### View logs on prod

```bash
docker logs -f oms-payment-service
docker logs -f oms-order-service
```

### Restart a single service on prod (no redeploy)

```bash
docker compose restart payment-service
```

Use this only if the container crashed — it does **not** pick up code or config changes.

### Restart with new config (env var change)

```bash
docker compose up -d payment-service
```

This recreates the container so the new env vars take effect.

### Access the database (Adminer)

- URL: `http://<prod-host>:8085`
- System: `PostgreSQL`
- Server: `oms-postgres`
- Username: `oms`
- Password: see `DB_PASSWORD` in the `.env` file on the server (`/opt/oms/.env`)

---

## Kafka consumer groups

Each service consumes from the `orders` topic with its own group ID:

| Service              | Group ID             |
|----------------------|----------------------|
| payment-service      | `payment-service`    |
| inventory-service    | `inventory-service`  |
| notification-service | `notification-service` |

This means all three services receive every `OrderCreated` event independently.

---

## When to use which restart command

| Situation                                    | Command                              |
|----------------------------------------------|--------------------------------------|
| Container crashed, no code/config change     | `docker compose restart <service>`   |
| Changed `docker-compose.yml` env vars/ports  | `docker compose up -d <service>`     |
| Changed Java source code or Dockerfile       | `docker build ... && docker compose up -d <service>` |
| Full clean reset (local only)                | `docker compose down -v && docker compose up -d` |
| Deploy to prod                               | Push to `main` → Jenkins handles it  |
