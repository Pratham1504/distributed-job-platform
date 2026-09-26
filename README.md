# Distributed Job Scheduling Platform

This repository implements the design in `job-platform-design-pack/job-platform-design`.

## Current milestone

The implemented MVP slice includes JWT sessions, project-scoped API keys, all four validated job request types, durable job/run/attempt history, PostgreSQL scheduling, a transactional outbox, RabbitMQ dispatch, worker leases and recovery, cancellation, manual retry, Redis submission quotas, worker health, and a React dashboard. The backend integration suite starts PostgreSQL, RabbitMQ, and Redis in containers to exercise those paths.

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

For Colima, export its socket before running Maven integration tests:

```sh
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Run the complete local stack

`docker-compose.full.yml` builds the API and dashboard and keeps PostgreSQL, RabbitMQ, Redis, and Prometheus private on the Compose network. Only the dashboard is published on `http://localhost:8088` by default.

```sh
docker-compose -f docker-compose.full.yml up --build
```

Add `--profile monitoring` to start Prometheus. Before using this stack, replace the placeholder passwords and JWT key in `.env`.

## Current execution guarantees

Job submission writes its logical job, run, and `JOB_READY` outbox event atomically. The publisher waits for RabbitMQ confirmation; workers claim a 30-second PostgreSQL lease and persist a typed result. Due schedules and retry waits are dispatched from PostgreSQL, not broker TTL. Lease recovery applies 10/30/120-second backoff before terminal failure and dead-letter publication. Manual retry creates a new run and external-effect identity; automatic retries retain the same effect identity.

The local handlers intentionally return deterministic result metadata. Real email, notification, artifact-storage, Azure Key Vault, TLS reverse proxy, off-host backup, load testing, and alert ownership still require production-provider credentials and operational deployment decisions; this repository does not claim those external controls are complete.
