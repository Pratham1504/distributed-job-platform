# API and Message Contracts

## 1. API rules

- Canonical OpenAPI document: `api/openapi.yaml`. It includes identity, personal projects, API-key lifecycle, project jobs, and operator worker health.
- Base path is `/api/v1`. Breaking changes require `/api/v2`; v1 changes must be additive.
- Jobs always live under `/projects/{projectId}`. A JWT user must own the project; an API key must be issued for that exact project. Such a key may submit/read/cancel/retry jobs, but cannot manage projects/keys or access operator endpoints.
- All request and response bodies are JSON UTF-8 and include/return `X-Correlation-Id`.
- Cursor pagination is ordered by `(created_at desc, id desc)` and is stable only for 15 minutes.
- Errors: `400` validation, `401` authentication, `403` operator permission, `404` inaccessible resource, `409` state/idempotency conflict, `429` quota with `Retry-After`.

## 2. Payload and result schemas

`CreateJobRequest` uses a strict discriminated `oneOf` of whole-request variants. Therefore `jobType: SEND_EMAIL` can only carry `EmailPayload`; a valid report payload cannot satisfy that request variant.

- `GENERATE_REPORT`: template, period start/end.
- `SEND_EMAIL`: approved template, recipient, variables.
- `PROCESS_FILE`: authorised object reference and permitted transformation.
- `SEND_NOTIFICATION`: fixed allowed channel, recipient, template.

Terminal handler outputs are defined in `contracts/handler-results.v1.json` and persisted as attempt `result_json`; `result_ref` is only for a large artifact. Unknown input fields are rejected in MVP. External provider response IDs and artifact references are returned as result metadata; sensitive payload fields are never echoed unnecessarily.

## 3. RabbitMQ routing and authority

| Exchange/queue | Purpose |
| --- | --- |
| `job.events` topic exchange | Outbox publisher entry point |
| `jobs.high`, `jobs.default`, `jobs.low` | Ready jobs by priority |
| `jobs.retry` | Reserved operational retry queue; not the scheduling authority in MVP |
| `jobs.dead-letter` | Messages that cannot be processed after final handling |

PostgreSQL controls one-time scheduling and retry timing. RabbitMQ only transports ready work.

`job-ready.v1.json` is the canonical `JOB_READY` schema. It carries `projectId`, `runId`, immutable automatic-retry `effectId`, and delivery-only `dispatchVersion`. The broker message must carry `messageId=eventId`, `correlationId`, `contentType=application/json`, and durable delivery mode.

## 4. Idempotency and retry contract

- Submit: unique `(projectId, Idempotency-Key)`; same request returns original `jobId`, changed body returns `409 IDEMPOTENCY_KEY_REUSED`.
- Automatic retry: same `runId` and same `effectId`; new `dispatchVersion` and attempt number are expected.
- Manual retry: only after final job failure, requires a fresh `Idempotency-Key`, creates a new run/new effect, and is durable-deduplicated by `(jobId, manualRetryKey)`.
- Maximum automatic attempts are 4 per run; maximum manual retry runs are 2 per job in MVP.

## 5. Contract versioning

- Message schema version is explicit (`schemaVersion: 1`).
- Additive optional fields are allowed within a version; removing/changing required fields requires a new version.
- Consumers ignore unknown optional fields and DLQ an unknown incompatible required version with a visible reason.

## 6. Webhook contract

Webhook is optional and excluded from MVP completion if time is limited. When enabled:

- Only terminal events are sent: `JOB_COMPLETED` and `JOB_FAILED`.
- Body follows `webhook-completed.v1.json`.
- Include `X-Webhook-Id`, `X-Webhook-Timestamp`, and `X-Webhook-Signature` (HMAC-SHA256 over timestamp + body).
- Retry non-2xx/timeouts at 1m, 5m, 30m. Webhook failure does not change terminal job status.
- Apply SSRF controls from the security design.
