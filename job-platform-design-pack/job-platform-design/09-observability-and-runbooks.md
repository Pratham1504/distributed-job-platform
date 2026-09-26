# Observability and Operational Runbooks

## 1. Dashboards

| Dashboard | Required panels |
| --- | --- |
| API | request rate, p95/p99 latency, 4xx/5xx, acceptance success |
| Queue | queue depth per priority, age of oldest message, publish failures, DLQ count |
| Workers | active workers, heartbeat age, active jobs, execution duration, timeout count |
| Jobs | terminal success/failure, retry rate, time-to-start by priority, schedule delay |
| Infrastructure | CPU, RAM, disk, DB connections, RabbitMQ memory/disk alarms |

## 2. Alerts and runbooks

| Alert | Trigger | Immediate response |
| --- | --- | --- |
| Stuck queue | oldest message > priority SLO | Check worker heartbeats, deployment, handler errors; add/restart worker after inspection |
| Growing DLQ | any new messages for 5 minutes | Inspect error code and schema; do not blindly requeue |
| Worker lost | heartbeat older than 60 seconds or lease expiry rising | Confirm container status, let recovery sweeper expire/abandon leases, then restart worker; never allow an old lease token to finalise |
| Outbox backlog | unpublished event older than 60 seconds | Check publisher logs/RabbitMQ; restart publisher after preserving DB |
| DB unavailable | failed health check for 1 minute | Stop intake if writes unsafe; recover DB, then reconcile outbox |
| Disk pressure | disk > 80% warning, >90% critical | Stop low-priority acceptance, prune safe logs/artifacts, expand disk before DB failure |

## 3. Recovery rules

- Never manually mark a job completed merely because an external side effect may have happened. Inspect handler idempotency evidence.
- Never bulk-requeue dead-letter messages without validating the causal defect is fixed.
- Every operator action affecting a job must generate an audit event.
- Maintain a short incident note with correlation IDs, timeline, impact, decision, and follow-up.
