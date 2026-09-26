# ADR-005: Execution Leases and Stable Effect Identity

## Decision

Every running attempt holds a renewable PostgreSQL lease with a random lease token. Recovery expires and abandons stale leases. Finalisation must match the current token, so a recovered worker cannot overwrite newer work.

Each automatic retry run owns one immutable `effect_id`. External side effects use this ID as their idempotency key. `dispatch_version` is only a queue-delivery version and changes on recovery/retry. A manual retry creates a new run and deliberately new `effect_id`.

## Alternatives considered

- Worker heartbeat alone: detects failure but cannot fence an old worker.
- Redis lock alone: lease loss and network partitions cannot safely prevent stale finalisation.
- `dispatch_version` as provider idempotency key: changes after timeout/retry and can duplicate side effects.

## Consequences

The database has an additional `job_runs` layer and lease metadata. The system makes a precise at-least-once guarantee: automatic reprocessing may occur, but a correctly integrated external provider sees the same effect key.

