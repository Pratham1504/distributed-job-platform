# Distributed Job Scheduling Platform

This repository implements the design in `job-platform-design-pack/job-platform-design`.

## Current milestone

The initial local foundation contains a Spring Boot backend, React dashboard shell, PostgreSQL/Flyway schema, and the first job submission/status API contracts. Docker-backed services will be added when Docker Desktop can start.

## Prerequisites

- Java 21
- Node.js 20 or later
- Docker Desktop later, for PostgreSQL, RabbitMQ, and Redis

## Layout

- `backend/` — Spring Boot API and database migrations
- `frontend/` — React + TypeScript dashboard
- `infra/` — local service configuration added with Docker enablement
- `job-platform-design-pack/` — approved architecture and delivery documents

## Next implementation sequence

1. Start PostgreSQL and apply Flyway migrations.
2. Add registration, login, refresh-token rotation, and project authorization.
3. Complete durable job submission with its transactional outbox event.
4. Add publisher, one report worker, and end-to-end integration tests.
