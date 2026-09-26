# Developer and Integration Guide

## 1. Local setup

Prerequisites: Java 21, Node 20+, Docker Desktop/Engine, Docker Compose, and Git.

1. Copy `.env.example` to `.env` and set non-default local passwords.
2. Start infrastructure with `docker compose up -d postgres rabbitmq redis`.
3. Start API and scheduler in development profile.
4. Start a worker with one registered handler.
5. Start the React application.
6. Visit API health and submit a sample job through dashboard or API.

## 2. Submit a job

Use a unique idempotency key for every logical user action. If the caller times out, repeat the same request with the same key rather than creating a new key. Create jobs under a project; API keys are scoped to one project.

```bash
curl -X POST http://localhost:8080/api/v1/projects/<project-id>/jobs \
  -H 'Authorization: Bearer <access-token>' \
  -H 'Idempotency-Key: report-2026-09-27-001' \
  -H 'Content-Type: application/json' \
  -d '{"jobType":"GENERATE_REPORT","payload":{"template":"SALES_SUMMARY","periodStart":"2026-09-01","periodEnd":"2026-09-30"},"priority":"DEFAULT"}'
```

## 3. Interpret status

- `PENDING`: waiting for its one-time scheduled time.
- `QUEUED`: accepted and waiting for worker capacity.
- `RUNNING`: handler has started.
- `RETRY_WAIT`: prior attempt failed temporarily; retry has been scheduled.
- `COMPLETED`, `FAILED`, `CANCELLED`: terminal states.

Do not assume a network timeout means job rejection. Query by job ID or repeat with the same idempotency key.

## 4. Add a handler

1. Add a `JobType` enum value and payload model.
2. Add strict payload validation/JSON schema.
3. Implement `JobHandler` and register it in Spring.
4. Define timeout, retryable errors, external-effect idempotency key, and result schema.
5. Add unit, integration, and failure/recovery tests.
6. Update OpenAPI and contracts if external client inputs or outputs change.

## 5. Troubleshooting

| Symptom | First checks |
| --- | --- |
| Job stays queued | queue depth, worker heartbeat/lease status, handler capacity, per-project limit |
| Job repeats | inspect `effectId`, dispatch version, and provider idempotency evidence |
| Job not published | outbox backlog and publisher logs |
| Scheduled job late | scheduler health, DB clock, due-job query, capacity |
| Job rejected | payload schema, idempotency-key reuse, quota, authorization |
