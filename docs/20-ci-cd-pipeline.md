# 20 — CI/CD Pipeline

## Overview

**GitHub Actions** for CI. **Argo CD** (or Helm + kubectl) for CD.

Every code change goes through automated validation before reaching any environment. Production deploys are gated on staging passing.

---

## Pipeline Stages

```
┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
│  Commit  │──▶│   CI     │──▶│  Stage   │──▶│  E2E     │──▶│  Prod    │
│  to PR   │   │ (test +  │   │  deploy  │   │  tests   │   │  deploy  │
│          │   │  build)  │   │          │   │  pass    │   │ (manual) │
└──────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘
```

---

## CI Workflow (on every push / PR)

`.github/workflows/ci.yml`

### Trigger
```yaml
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]
```

### Jobs (run in parallel where possible)

**1. lint-and-format**
- Checkstyle for Java code style
- Prettier for frontend
- Fail fast on style violations

**2. unit-tests**
- `mvn test` for each service
- Upload JaCoCo coverage report
- Fail if coverage < 80%

**3. integration-tests**
- `mvn verify -P integration-tests`
- Testcontainers starts PostgreSQL, Kafka, Redis
- Run integration test suite
- Upload test results

**4. security-scan**
- OWASP Dependency Check: scan Maven dependencies for known CVEs
- Trivy: scan Docker images for vulnerabilities
- Semgrep: static analysis for security anti-patterns
- Fail build if HIGH or CRITICAL CVEs found in runtime dependencies

**5. build-docker-images**
- Runs only after tests pass
- Builds Docker image for each service
- Tags with: `git SHA` and `branch-name`
- Pushes to container registry (GitHub Container Registry or ECR)

---

## Branch Strategy

```
main         → production-ready code; protected; requires PR + CI green
develop      → integration branch; auto-deploys to staging
feature/*    → feature branches; CI runs on push; merged to develop via PR
hotfix/*     → urgent prod fixes; merged to main and backported to develop
```

### PR Rules (branch protection on `main`)
- At least 1 approval required
- All CI checks must pass
- No direct pushes to `main`
- Branch must be up-to-date with `main` before merge

---

## CD: Staging

**Trigger**: merge to `develop` branch

**Steps:**
1. CI passes (already verified)
2. GitHub Actions calls `kubectl set image` or triggers Argo CD sync
3. Kubernetes performs rolling update of each service
4. Wait for all pods readiness probes to pass
5. Trigger E2E test suite against staging URL
6. Notify `#deployments` Slack channel: success or failure

**Rollback:** `kubectl rollout undo deployment/order-service -n oms-staging`

---

## CD: Production

**Trigger**: Manual approval after staging E2E passes

GitHub Actions environment with `required_reviewers`:
```yaml
environment:
  name: production
  url: https://api.oms.example.com
```

A human reviews the staging results and clicks "Approve" in GitHub Actions UI.

**Steps:**
1. Tag the Docker images with the release version (semver)
2. Apply Kubernetes manifests to production namespace
3. Rolling update — Kubernetes replaces pods gradually
4. Smoke test: hit `/actuator/health` for each service
5. Monitor error rate for 10 minutes — if > 1%, auto-rollback

---

## Environment Variables per Environment

Managed as Kubernetes Secrets, injected as env vars into pods.

Never hardcode secrets. CI uses GitHub Actions Secrets for registry credentials.

```yaml
env:
  DATABASE_URL: ${DATABASE_URL}        # from Kubernetes Secret
  KAFKA_BOOTSTRAP: ${KAFKA_BOOTSTRAP}
  REDIS_HOST: ${REDIS_HOST}
  STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY}
  JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY}
```

---

## Database Migrations in CD

Flyway migrations run automatically on service startup (`spring.flyway.enabled=true`).

- In rolling updates, the new version's migrations run while old pods are still serving traffic
- Therefore: migrations must always be **backward compatible** with the previous service version
  - Safe: `ADD COLUMN` with a default, `CREATE INDEX CONCURRENTLY`
  - Unsafe: `DROP COLUMN`, `RENAME COLUMN` (old pods still use old column name)
- Multi-step migration for destructive changes: add column → deploy → backfill → drop old column → deploy

---

## Artifact Versioning

Every Docker image tagged with:
- `sha-{gitShortSHA}` — immutable, traceable to exact commit
- `latest` on `main` branch (convenience, never rely on in prod)
- `v1.2.3` on tagged releases

Never mutate an existing image tag — always create a new one.

---

## Pipeline Monitoring

- Deployment frequency: number of successful deploys per week
- Change failure rate: % of deploys that required a rollback
- Mean time to recovery (MTTR): average time from failure detection to recovery
- Lead time: time from commit to production

Track these via DORA metrics dashboard in Grafana.
