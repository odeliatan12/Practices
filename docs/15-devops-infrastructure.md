# 15 — DevOps & Infrastructure

## Infrastructure Overview

| Environment | Platform | Purpose |
|---|---|---|
| Local | Docker Compose | Development |
| Staging | Kubernetes (cloud) | Integration testing, QA |
| Production | Kubernetes (cloud) | Live traffic |

Cloud provider: AWS (but design is cloud-agnostic — can run on GCP or Azure with minor changes).

---

## Docker

### Each Service Has a Dockerfile

```dockerfile
# Multi-stage build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build guidelines:
- Use multi-stage builds — final image has no JDK, only JRE
- No root user in production image (`USER 1000`)
- `.dockerignore` excludes `target/`, `.git/`, local config files

---

## Docker Compose (Local Dev)

`docker-compose.yml` at project root runs the full system locally:

```yaml
services:
  postgres:       # single PostgreSQL instance, multiple databases
  kafka:          # single Kafka broker (KRaft mode, no ZooKeeper)
  redis:          # single Redis node
  elasticsearch:  # single ES node
  minio:          # S3-compatible local object storage
  
  api-gateway:    # depends_on: [order-service, user-service, ...]
  order-service:  # depends_on: [postgres, kafka, redis]
  inventory-service:
  payment-service:
  fulfillment-service:
  notification-service:
  user-service:
  search-indexer:
  
  frontend:       # Vite dev server
```

Startup order managed by `depends_on` with `condition: service_healthy`.

---

## Kubernetes (Staging / Production)

### Resource per Service

Each microservice gets:
- **Deployment**: manages pods with rolling updates
- **Service**: internal ClusterIP for service discovery
- **ConfigMap**: non-secret configuration
- **Secret**: sensitive values (DB passwords, API keys)
- **HorizontalPodAutoscaler (HPA)**: scales pods based on CPU/memory

```
k8s/
├── namespaces/
│   └── oms-prod.yaml
├── order-service/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   ├── secret.yaml         ← DO NOT commit real secrets here
│   └── hpa.yaml
├── inventory-service/
├── ...
├── infrastructure/
│   ├── kafka/
│   ├── redis/
│   ├── postgres/
│   └── elasticsearch/
└── ingress/
    └── ingress.yaml        ← routes external traffic to API gateway
```

### Deployment Strategy
- **Rolling update** (default): zero-downtime, replaces pods gradually
- **Readiness gates**: new pods only receive traffic once `/actuator/health/readiness` returns UP
- **Pod Disruption Budget**: at least 1 pod always available during node maintenance

### Resource Requests and Limits
```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "250m"
  limits:
    memory: "512Mi"
    cpu: "500m"
```

Start conservative, adjust based on actual metrics.

---

## Networking

### Internal (within cluster)
- Services communicate via Kubernetes DNS: `http://order-service.oms-prod.svc.cluster.local:8081`
- No external network hop — all internal traffic stays in-cluster
- mTLS via Istio service mesh (optional, but recommended for prod)

### External
- NGINX Ingress Controller routes `api.oms.example.com/*` → API Gateway Service
- TLS terminated at the Ingress (certificate from cert-manager + Let's Encrypt)
- Frontend served by CloudFront CDN or Vercel

---

## Secrets Management

- **Local**: `.env` file (gitignored) loaded by Docker Compose
- **Kubernetes**: Kubernetes Secrets (base64-encoded, etcd-encrypted at rest)
- **Production best practice**: Secrets stored in AWS Secrets Manager, synced to Kubernetes Secrets via External Secrets Operator

Never store secrets in:
- Git repository (even private)
- Docker image
- ConfigMaps (not encrypted)
- Application logs

---

## Database in Kubernetes

For production:
- Use **AWS RDS** (PostgreSQL managed service) — not Postgres in a pod
- Managed backups, failover, and patching
- Connection from pods via Service resource pointing to RDS endpoint

For staging / local:
- PostgreSQL StatefulSet with a PersistentVolumeClaim

---

## Kafka in Kubernetes

For production:
- Use **AWS MSK** (Managed Streaming for Kafka) — not self-hosted
- Or: Strimzi operator for self-managed Kafka on Kubernetes

For local dev:
- Single-broker Kafka in Docker Compose (KRaft mode)

---

## Monitoring Stack

Runs in `monitoring` namespace:
- **Prometheus**: scrapes `/actuator/metrics` from all services
- **Grafana**: dashboards connected to Prometheus
- **Alertmanager**: routes alerts to Slack / PagerDuty
- **Loki**: log aggregation (shipped by Promtail from each pod)

See [doc 16](16-monitoring-observability.md) for details.

---

## Infrastructure as Code

Use **Terraform** for cloud resources:
- VPC, subnets, security groups
- RDS instances
- MSK cluster
- S3 buckets
- IAM roles

Terraform state stored in S3 with DynamoDB lock table.

```
terraform/
├── modules/
│   ├── rds/
│   ├── msk/
│   └── eks/
├── environments/
│   ├── staging/
│   └── production/
└── variables.tf
```
