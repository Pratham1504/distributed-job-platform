# Low-Level Design

## 1. Modules

| Module | Responsibility |
| --- | --- |
| `identity` | Users, JWT, refresh tokens, API-key authentication |
| `projects` | Personal project ownership, API-key scope, quotas |
| `jobs` | Logical jobs, state machine, request validation, authorisation |
| `runs` | Retry-chain budgets and effect identity |
| `outbox` | Atomic dispatch intent, publisher lease, reconciliation |
| `scheduling` | Claim due one-time jobs and create outbox events |
| `messaging` | RabbitMQ exchanges, confirms, consumer setup |
| `execution` | Attempt lease, heartbeat, timeout, recovery, finalisation |
| `handlers` | Typed implementations of supported job types |
| `artifacts` | Safe result-object references |
| `audit` | Immutable security and lifecycle events |
| `observability` | Metrics, logs, correlation IDs, health endpoints |

## 2. Core concepts

- A **job** is the client’s enduring intent: “send this order email.”
- A **run** is one automatic retry chain. A job starts with run 1. All automatic attempts in that run share one immutable `effect_id`.
- An **attempt** is one worker execution in a run.
- A **manual retry** explicitly creates run 2, with a new `effect_id` and its own idempotency key. It means “try this business action again,” not “replay an unknown prior effect.”
- `dispatch_version` identifies a queue delivery instruction. It can change on retry/recovery but is never used as the external-effect idempotency key.

## 3. Core interfaces

```java
public interface JobHandler<P extends JobPayload> {
    JobType type();
    PayloadValidationResult validate(P payload);
    HandlerResult execute(JobExecutionContext context, P payload) throws RetryableJobException;
}

public record JobExecutionContext(
    UUID jobId, UUID runId, UUID attemptId, UUID effectId,
    UUID leaseToken, String correlationId
) {}

public interface ExecutionLeaseService {
    boolean renew(UUID attemptId, UUID leaseToken, Instant now);
    boolean finalizeAttempt(UUID attemptId, UUID leaseToken, HandlerResult result, Instant now);
}
```

Handlers are registered server-side by `JobType`. A client never supplies a class name, command, arbitrary URL, or executable.

## 4. Transaction boundaries

### Submit immediate job

One PostgreSQL transaction:

1. Authenticate project, validate payload, quota, and idempotency key.
2. Insert `jobs` in `QUEUED` state.
3. Insert run 1 with immutable `effect_id` and automatic-attempt budget.
4. Insert `outbox_events` `JOB_READY` for run 1, dispatch version 1.
5. Insert audit event and commit.

The outbox publisher claims unpublished events with `FOR UPDATE SKIP LOCKED`, sends with RabbitMQ publisher confirms, and records `published_at`. It may publish twice after a crash; this is expected and safe.

### Schedule or retry dispatch

One-time schedules and `RETRY_WAIT` are controlled by PostgreSQL, not RabbitMQ TTL. A dispatcher transaction selects due rows with `FOR UPDATE SKIP LOCKED`, changes job/run to `QUEUED`, increments `dispatch_version`, inserts one `JOB_READY` outbox event, and commits.

RabbitMQ contains ready work only. `jobs.retry` may exist operationally but is not the scheduling authority in MVP.

### Worker claim and lease

1. Consumer reads a `JOB_READY` message but does not acknowledge it.
2. In one transaction, conditionally claim the job/run only if it is `QUEUED`, the `dispatch_version` matches, and cancellation is absent.
3. Insert attempt `RUNNING` with random `lease_token`, `lease_expires_at = now + 30s`, and `worker_epoch`; increment run attempt count; commit.
4. Worker renews its lease every 10 seconds with a conditional update matching `attempt_id`, `lease_token`, and `RUNNING` state.
5. Run handler outside a DB transaction, bounded by 10 minutes.
6. Finalise only with a conditional transaction matching current `lease_token`, `RUNNING`, and unexpired lease. Then acknowledge the broker message.

If finalisation loses the lease race, the stale worker must not change job state or acknowledge as successful. It stops work and records an operational log only.

### Recovery sweeper

Every 15 seconds, a recovery worker scans `RUNNING` attempts whose `lease_expires_at < now` using `FOR UPDATE SKIP LOCKED`.

1. Mark the expired attempt `ABANDONED`.
2. Mark its run `RETRY_WAIT` if automatic budget remains, otherwise `FAILED`.
3. Update the logical job summary state.
4. Set a durable `next_dispatch_at` for retry, or atomically insert `JOB_DEAD_LETTERED` outbox event with final failure on budget exhaustion.
5. Commit.

The recovered attempt’s lease token is no longer valid, so an old worker cannot finalise it. It might already have caused an external effect; stable `effect_id` keeps automatic re-execution safe.

## 5. State transitions

| Resource | Current | Event | Next | Rule |
| --- | --- | --- | --- | --- |
| Job | `PENDING` | schedule due | `QUEUED` | dispatcher creates outbox event |
| Job | `PENDING`/`QUEUED` | owner cancel | `CANCELLED` | future dispatch blocked |
| Run | `QUEUED` | worker lease claim | `RUNNING` | one DB claim succeeds |
| Attempt | `RUNNING` | lease expiry | `ABANDONED` | sweeper fences old worker |
| Run | `RUNNING` | retryable failure/abandonment | `RETRY_WAIT` | PostgreSQL sets next dispatch time |
| Run | `RETRY_WAIT` | due | `QUEUED` | new outbox event and dispatch version |
| Run | `RUNNING` | success | `COMPLETED` | attempt terminal |
| Run | `RUNNING` | permanent failure/budget exhausted | `FAILED` | same transaction stores final attempt/result/error and inserts `JOB_DEAD_LETTERED` outbox event |
| Job | `FAILED` | manual retry with new request key | `QUEUED` | new run and new effect ID |

`CANCELLED`, `COMPLETED`, and `FAILED` are terminal for automatic processing. A worker checks cancellation again during its database claim.

## 6. Concurrency and duplicate control

- PostgreSQL conditional updates are the final ownership and fencing mechanism; Redis locks are advisory only.
- Unique `(project_id, idempotency_key)` enforces submission deduplication.
- Unique `(job_id, run_number)` and `(job_id, manual_retry_key)` enforce durable manual retry chains.
- Unique `(run_id, attempt_number)` prevents duplicate attempts.
- `effect_id` is stable across automatic retries and recovery for a run. External effects use it, e.g. `email:{effectId}`.
- `dispatch_version` only suppresses stale messages; it must never be used as the external-effect idempotency key.

## 7. Failure sequences

### API crashes after DB commit

The job, run, and outbox event are durable. The publisher finds the unpublished event later.

### Publisher crashes after broker confirmation

The message may be republished. Consumer conditional claim rejects stale/duplicate dispatches.

### Worker crashes after external effect

The broker redelivers or the lease sweeper schedules recovery. The new attempt reuses the same `effect_id`; the external provider or durable handler dedupe must return the same effect result rather than repeat it.

### Stale worker resumes after recovery

Its `lease_token` no longer matches. Its renewal/finalisation conditional update fails, so it cannot overwrite recovered state. It must stop and leave message acknowledgement to broker redelivery/recovery.

### Broker data is lost after VM loss

Reconciliation considers every non-terminal run whose latest dispatch was marked published but has no live, unexpired attempt. It creates a new outbox dispatch with a higher version. Duplicates are safe through the stable `effect_id`.

### Final failure and DLQ publication

In one finalisation/recovery transaction, mark the attempt terminal, mark run/job `FAILED`, write error/result fields, append audit event, and insert an outbox `JOB_DEAD_LETTERED` event. The outbox publisher routes that event to `jobs.dead-letter` with publisher confirms. A broker outage can delay DLQ visibility, but cannot lose the final-failure event after the DB transaction commits.
