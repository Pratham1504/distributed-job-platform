# Product Requirements and Acceptance Criteria

## 1. Product statement

The platform accepts non-interactive tasks from a dashboard or external application, executes predefined tasks in the background, and gives the caller a durable status trail. It prevents slow jobs from blocking user-facing API responses.

## 2. Intended users

| User | Need | MVP access |
| --- | --- | --- |
| Dashboard user | Create a personal project, submit and monitor its jobs | Full access to own projects/resources |
| API client application | Submit tasks programmatically and query results | Project-scoped API key |
| Platform operator | Inspect health, queues, and audit events | Local deployment/operator access only |

MVP uses **personal ownership through projects**: every account owns one or more projects; each job, API key, quota, audit event, and result artifact belongs to exactly one project. A future organization workspace can own projects without changing job semantics.

## 3. Supported job types

| Type | Input | Output |
| --- | --- | --- |
| `GENERATE_REPORT` | report template and parameters | Result metadata and optional file URL |
| `SEND_EMAIL` | approved template, recipient, template variables | Provider message ID |
| `PROCESS_FILE` | approved Blob/object reference and transformation | Result object reference |
| `SEND_NOTIFICATION` | recipient, channel, template | Delivery provider response |

All inputs are schema-validated. The service will never accept a shell command, uploaded executable, arbitrary JavaScript, or arbitrary container image as a job payload.

## 4. MVP functional requirements

1. A user can register, log in, create and revoke an API key, and submit a job.
2. A submitted immediate job is durably accepted before a success response is returned.
3. A submitted one-time scheduled job remains `PENDING` until its scheduled UTC time, then becomes eligible for execution.
4. A client can query the logical job and all its execution attempts.
5. A failed retryable attempt is retried at most three times with configured backoff.
6. A permanently failed job is visible as `FAILED` and its message is retained in a dead-letter queue.
7. A client can cancel a job only while it is `PENDING` or `QUEUED`.
8. A client can request a manual retry only after final failure. It requires a new idempotency key, creates a new run with a new external effect identity, and is limited to two manual retry runs per job.
9. Dashboard users can see job status, attempt history, error summaries, and worker health.

## 5. Exact behaviour

### Submission

- `POST /api/v1/projects/{projectId}/jobs` requires valid JWT or a key for that exact project, plus `Idempotency-Key`.
- One key maps to one logical job for that authenticated owner. Repeating the same key and identical request returns the original response. Reusing the key with different body hash returns `409 IDEMPOTENCY_KEY_REUSED`.
- The API inserts a job and an outbox event in the same PostgreSQL transaction. It returns `202 Accepted` only after the transaction commits.

### Retry

- Retryable failures: temporary provider error, network timeout, broker redelivery, explicit handler retry result.
- Non-retryable failures: invalid payload, permission error, unsupported job type, permanent provider rejection.
- Default retry delays are 10 seconds, 30 seconds, and 120 seconds. Attempts are counted separately from the logical job.
- A job stays logically active while its run is `RETRY_WAIT`; the old attempt is terminal with `RETRY_SCHEDULED`.

### Cancellation

- `PENDING` cancellation prevents publication.
- `QUEUED` cancellation records a cancellation request. A worker checks status immediately before execution and skips a cancelled job if it has not started.
- `RUNNING` returns `409 JOB_ALREADY_RUNNING`; the MVP does not forcibly interrupt side effects.
- Cancellation is best effort because a broker message may already be delivered.

### One-time schedules

- Client submits `scheduledAt` in ISO-8601 UTC.
- Time must be between now + 10 seconds and now + 30 days.
- On scheduler restart, a due job is claimed and published once using the durable outbox path.
- A scheduled time in the past is rejected; there is no implicit misfire policy in MVP.

## 6. Acceptance criteria

| ID | Given | When | Then |
| --- | --- | --- | --- |
| AC-01 | valid authenticated client | submits immediate valid job with new idempotency key | receives `202`, `jobId`, and `QUEUED` or `PENDING`; job and outbox record exist |
| AC-02 | same client and same request | repeats identical idempotency key | receives same `jobId`; no second job or outbox record is created |
| AC-03 | accepted job | publisher temporarily fails | job remains recoverable; publisher later publishes from the outbox |
| AC-04 | job is queued | worker succeeds | one completed attempt is stored and logical job is `COMPLETED` |
| AC-05 | handler returns retryable failure | retry budget remains | old attempt is `RETRY_SCHEDULED`; run is `RETRY_WAIT`; PostgreSQL dispatcher creates the next outbox event when due |
| AC-06 | handler fails after retry budget | retry budget exhausted | job is `FAILED`, a dead-letter message exists, and error is visible |
| AC-07 | job is pending or queued | owner cancels it | job becomes `CANCELLED` and no handler side effect starts |
| AC-08 | job is running | owner cancels it | response is `409`; running work continues to a final state |
| AC-09 | one-time scheduled job | scheduled time arrives | it is published no earlier than its time and is normally started within its scheduling SLO |
| AC-10 | user A and user B | user B reads user A project/job ID | API returns `404` to avoid resource enumeration |
| AC-11 | worker lease expires | recovery sweeper runs | attempt becomes `ABANDONED`; stale lease cannot finalise it; the run is retried or failed by budget |
| AC-12 | email provider times out after accepting email | automatic retry occurs | retry reuses same `effectId`; provider returns the original effect rather than sending a duplicate |
