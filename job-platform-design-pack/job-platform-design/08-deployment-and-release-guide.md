# Deployment and Release Guide

## 1. Environment topology

| Environment | Purpose | Topology |
| --- | --- | --- |
| Local | Development and tests | Docker Compose on developer machine |
| Demo | Public portfolio demo | One Azure Linux VM, Docker Compose, private application network |
| Future production | Not yet approved | Must meet availability, backup, and security checklist |

Recommended demo VM starting point: 2 vCPU, 8 GiB RAM, 64 GiB disk. If constrained, run one worker and disable Grafana until core flow is stable. PostgreSQL and RabbitMQ use named persistent volumes.

## 2. Network and exposure

- Public: HTTPS reverse proxy to React app and `/api` only.
- Private Docker network: PostgreSQL 5432, RabbitMQ 5672/15672, Redis 6379, Prometheus 9090, Grafana 3000.
- Do not expose database, Redis, RabbitMQ management, Prometheus, or Grafana publicly.
- Use a TLS certificate from a managed certificate issuer; redirect HTTP to HTTPS.

## 3. Configuration

- Store non-secret defaults in versioned `.env.example`.
- Store actual secrets in deployment environment or Azure Key Vault when available.
- Set a unique `APP_ENV`, `DATABASE_URL`, broker and Redis credentials, JWT signing key, and object-storage credentials.
- Use read-only application file system where practical.

## 4. Release sequence

1. CI runs tests and builds immutable image tags.
2. Back up PostgreSQL and verify disk headroom.
3. Pull images to VM.
4. Run Flyway migration job exactly once.
5. Start API and scheduler; verify health endpoints.
6. Start workers one at a time; verify heartbeat and queue consumption.
7. Run smoke test: submit report job and verify final status.
8. Announce/mark release only after smoke test passes.

## 5. Worker draining and rollback

- Before deployment, set workers to draining: stop consuming new messages but finish active jobs up to 10 minutes.
- Deploy compatible schema/application changes only.
- If new application health checks fail, stop new containers and restore prior image tag. Do not roll back a destructive DB migration; migrations must use expand/contract strategy.
- Reconcile unpublished outbox records, published-but-undelivered queued runs, and expired in-progress attempt leases after rollback.

## 6. Cost and scope note

The design intentionally self-hosts the runtime. Azure VM, public IP, managed storage, and outbound traffic may have charges once free/student credits or allowances are exceeded. Enable a budget alert before deployment. Blob Storage is permitted only for artifact/backup use and is not assumed zero-cost.
