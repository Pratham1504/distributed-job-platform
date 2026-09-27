# Distributed Job Scheduling Platform

This repository implements the current product specification in `job-platform-design-pack/job-platform-design 2`, especially `13-data-automation-product-mvp.md`. The earlier design pack remains the reusable execution-engine reference where it does not conflict with the extension.

## Current milestone

The implemented application includes JWT sessions, project-scoped API keys, durable job/run/attempt history, PostgreSQL scheduling, a transactional outbox, RabbitMQ dispatch, weighted priority consumers, leases and recovery, cancellation, manual retry, Redis quotas, structured observability, and a React dashboard. The primary product flow is secure CSV upload, background validation or normalization, and authorised cleaned/error CSV downloads. The backend integration suite starts PostgreSQL, RabbitMQ, and Redis in containers to exercise the end-to-end path.

## Prerequisites

- Java 21
- Node.js 20 or later
- Docker Desktop or Colima, for PostgreSQL, RabbitMQ, and Redis

## Layout

- `backend/` — Spring Boot API and database migrations
- `frontend/` — React + TypeScript dashboard
- `monitoring/` — Prometheus scrape configuration
- `job-platform-design-pack/` — approved architecture and delivery documents

## Run locally during development

1. Copy `.env.example` to `.env` and set non-default local passwords.
2. Start PostgreSQL, RabbitMQ, and Redis with `docker-compose up -d`.
3. Start the backend with Java 21, then the frontend with Node 20+.
4. Run `mvn test` in `backend/`; Docker enables the container-backed integration suite.

## Test coverage

The backend suite validates the documented OpenAPI operation inventory, a V2-to-current Flyway upgrade with existing data, full PostgreSQL/RabbitMQ/Redis job flow, confirmed RabbitMQ dead-letter routing, and lease recovery after a worker stops between claim and finalisation.

The dashboard has browser-level route and accessibility coverage. Run it after installing frontend dependencies:

```sh
cd frontend
npm run test:e2e:install
npm run test:e2e
```

For Colima, export its socket before running Maven integration tests:

```sh
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Run the complete local stack

`docker-compose.full.yml` builds the API and dashboard and keeps PostgreSQL, RabbitMQ, Redis, and Prometheus private on the Compose network. The dashboard binds only to `http://localhost:8088` by default.

```sh
docker-compose -f docker-compose.full.yml up --build
```

### Public HTTPS demo deployment

For a VM with a public DNS record, set `PUBLIC_HOSTNAME` and `CADDY_EMAIL` in `.env`, open only TCP ports 80 and 443 in the VM network security group, then start the public profile:

```bash
docker compose -f docker-compose.full.yml --profile public up -d --build
```

Caddy terminates TLS, redirects HTTP to HTTPS, and is the only internet-facing container. Keep the direct dashboard port (`8088`) bound to loopback and do not expose PostgreSQL, RabbitMQ, Redis, Prometheus, or Grafana publicly.

Add `--profile monitoring` to start Prometheus and Grafana. They bind only to localhost at `http://localhost:9090` and `http://localhost:3000`; Grafana uses the credentials in `.env`. Before using this stack, replace the placeholder passwords and JWT key in `.env`.

## Current execution guarantees

Job submission writes its logical job, run, and `JOB_READY` outbox event atomically. The publisher waits for RabbitMQ confirmation; 5:3:1 consumer pools favor high/default/low priorities while the database applies global and per-project execution limits. Workers claim a renewable 30-second PostgreSQL lease and persist a typed result. Due schedules and retry waits are dispatched from PostgreSQL, not broker TTL. Reconciliation reissues an old ready event if a broker message is lost. Lease recovery applies 10/30/120-second backoff before terminal failure and dead-letter publication. Manual retry creates a new run and external-effect identity; automatic retries retain the same effect identity.

Report jobs write a project-scoped JSON artifact through the local storage adapter. CSV processing uploads create a durable, project-owned `file_assets` record; the queued job contains its UUID `sourceAssetId`, never a storage path or URL. A completed file job includes real total/valid/rejected/duplicate counts and links to authorised cleaned and error CSV downloads. Local artifacts and file assets are retained for 30 days by default; job metadata, attempts, published outbox events, and audit records are cleaned on the documented 90/30/14/180-day policies.

## Operations

Application logs are structured JSON and include a correlation ID where a request initiated the work. Prometheus publishes product metrics for queue depth, running executions, healthy workers, outbox age, submissions, outcomes, retries, and lease failures. The supplied Grafana dashboard and Prometheus alerts cover the primary queue/worker/outbox conditions in the design runbook.

Create a local logical PostgreSQL backup with:

```sh
sh scripts/backup-postgres.sh
```

The compressed output is intentionally ignored by Git. Encrypt and copy it to approved off-host storage, then perform the documented restore drill. To restore, provision an empty target database and run `sh scripts/restore-postgres.sh backups/<file>.sql.gz`.

The email and notification adapters intentionally return deterministic local provider IDs, so automatic attempts remain idempotent in a credential-free environment. Real email/notification providers, cloud object storage, Azure Key Vault, public TLS/reverse proxy, encrypted off-host backup delivery, load-test evidence, alert routing/ownership, and an executed restore drill require provider credentials and deployment authority. Those external controls must be completed before calling the system production-ready.

## Local release checks

The platform protects sign-in attempts by email fingerprint and client address. Defaults are 10 attempts per email and 30 attempts per address per minute; adjust only through the `LOGIN_*` settings in `.env`. A comma-separated `OPERATOR_EMAILS` allowlist grants the deployment-level operator role. It is never selectable from the registration form.

Low-priority submissions are shed first when the global waiting queue reaches 5,000 jobs or the local file-assets volume reaches 80% capacity. Prometheus and the provisioned Grafana dashboard expose both values and admission rejections.

To collect the load-test evidence required by the design, copy `scripts/load-targets.example.json` to the ignored `scripts/load-targets.json`, fill it with local project IDs and API keys, then run:

```sh
LOAD_RATE_PER_SECOND=5 LOAD_DURATION_SECONDS=300 node scripts/load-test-local.mjs
```

The default per-project quota is 60 submissions/minute, so use at least five test projects/API keys for the 5 jobs/second scenario, or raise the quota only in an isolated test environment. The script reports accepted, rate-limited, failed, p50, p95, and p99 submission latency and exits non-zero for non-rate-limit failures or a p95 over 500 ms.

For a restore drill, first create a backup, then verify it in a temporary database. The verifier never restores into the application database and removes its temporary database after checking the schema and core tables:

```sh
sh scripts/backup-postgres.sh
sh scripts/verify-backup-restore.sh backups/<backup-file>.sql.gz
```
