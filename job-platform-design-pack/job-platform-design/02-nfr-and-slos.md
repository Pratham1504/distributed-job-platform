# Nonfunctional Requirements and Service Objectives

## 1. Capacity model for MVP

The first deployment is a single Azure VM. Objectives deliberately reflect a portfolio/demo system, not an HA commercial platform.

| Metric | Target | Enforcement / measurement |
| --- | --- | --- |
| Sustained accepted submissions | 5 jobs/second | API metrics over 5 minutes |
| Burst submissions | 60 jobs in 60 seconds per user | Redis rate limiter |
| Per-project queued jobs | 1,000 | API rejects excess with `429` |
| Active executions | 4 total, 2 per project | Worker semaphore / database claim |
| Payload size | 64 KiB JSON | Request validation; files passed by object reference only |
| Maximum job duration | 10 minutes | Worker timeout; job becomes retryable timeout unless handler says otherwise |
| Artifact size | 50 MiB | Object storage reference limit |
| Job metadata retention | 90 days | Scheduled purge after export/audit retention |
| Attempt log retention | 30 days | Retention cleanup |
| Dead-letter retention | 14 days | Alert, review, then purge/archive |

## 2. User-facing SLOs

| Service indicator | Objective | Error budget / scope |
| --- | --- | --- |
| Job acceptance availability | 99.5% monthly | `POST /jobs` returns a durable result or explicit error |
| Acceptance API latency | p95 <= 500 ms; p99 <= 1 s | Excludes client network and payload upload |
| Status-read latency | p95 <= 300 ms | `GET /jobs/{id}` |
| High-priority start delay | 95% start within 15 seconds | Measured from API acceptance to first handler start when capacity is available |
| Default-priority start delay | 95% start within 60 seconds | Same conditions |
| Low-priority start delay | 95% start within 5 minutes | Same conditions |
| One-time scheduling delay | 95% start within 30 seconds after scheduled time | Capacity available; excludes planned maintenance |
| State accuracy | 99.9% of terminal attempts have a final durable record | Reconciliation report |

Queue delay is a primary user experience metric. API uptime alone is insufficient if accepted jobs remain stuck.

## 3. Availability, data loss, and recovery objectives

| Objective | MVP target | Consequence |
| --- | --- | --- |
| Availability | 99.5% monthly | Single VM is a known single failure domain |
| RPO | 24 hours until off-host backups are verified; goal 1 hour after backup automation | VM loss can lose data after last backup |
| RTO | 4 hours | Rebuild VM, restore DB, redeploy stack, reconcile outbox |
| Message delivery | At least once | Handler must tolerate duplicates |
| Data-loss guarantee | No acknowledged job loss under normal DB/broker operation; VM catastrophe limited by RPO | Outbox closes the DB-to-queue gap |

Production launch requires a tested off-host backup process. A single local volume does not satisfy the RPO claim.

## 4. Fairness and overload policy

- Weighted dispatch: high 5, default 3, low 1. After five high-priority messages, workers must inspect lower-priority queues if eligible work exists.
- Per-project concurrent execution limit: two.
- Per-project rate limit: 60 submissions/minute, with a configurable lower plan limit.
- On quota breach, return `429` with `Retry-After`; do not silently drop jobs.
- On queue depth above 5,000 or disk use above 80%, stop accepting low-priority jobs first and alert the operator.
