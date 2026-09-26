# ADR-001: At-least-once Delivery with Transactional Outbox

## Decision

Use PostgreSQL as the source of truth, insert the logical job and an outbox event in one transaction, then publish through a separately retried outbox publisher. Guarantee at-least-once delivery, not exactly-once execution.

## Alternatives considered

- Publish directly to RabbitMQ after writing the job: can lose jobs if process crashes between DB commit and publish.
- Two-phase commit: disproportionate complexity and unavailable for this project.
- Redis lock as duplicate guarantee: not sufficient for external side effects.

## Consequences

Outbox publishing can duplicate messages. Consumers and handlers must be idempotent. This adds schema and worker complexity but avoids silent acknowledged-job loss.

