# Backup and Disaster-Recovery Plan

## 1. Backup scope

- PostgreSQL: users, jobs, attempts, outbox, audit events.
- Application configuration excluding secrets; secrets are backed up separately by secure secret-management procedure.
- Result artifacts and generated reports stored off-host.
- Docker image tags and deployment compose files.

RabbitMQ messages are recoverable from durable queues under normal VM restart, but a VM-loss event can lose local broker data. PostgreSQL plus run/attempt state and the outbox is the reconciliation source.

## 2. Backup policy

| Item | Frequency | Retention | Location |
| --- | --- | --- | --- |
| Encrypted PostgreSQL logical backup | Daily | 14 daily copies | Off-host Azure Blob Storage or equivalent |
| PostgreSQL WAL/incremental backup | R2 target hourly | 7 days | Off-host storage |
| Result artifacts | At creation | 30 days default | Object storage |
| Compose/config release bundle | Each release | 10 releases | Git and off-host release storage |

MVP RPO is 24 hours until the hourly backup target is implemented and proven.

## 3. Restore procedure

1. Provision clean VM and private network.
2. Install Docker and retrieve approved image tags/configuration.
3. Restore PostgreSQL backup to empty database.
4. Verify row counts and migration version.
5. Start PostgreSQL, RabbitMQ, Redis, API, and scheduler; keep workers paused.
6. Reconcile every non-terminal run, not only unpublished outbox records: (a) publish unpublished events, (b) find `QUEUED` runs whose last dispatch event was marked published but has no live message/attempt, and create a new outbox dispatch with incremented version, and (c) identify `RUNNING` attempts without a live, unexpired lease.
7. Mark stale attempts `ABANDONED`, queue recoverable runs using the documented retry policy, and review effect-idempotency evidence before replay.
8. Start workers, perform smoke test, and record recovery time.

## 4. Restore drill evidence

Before production launch, run a restore drill at least once. Record backup timestamp, restore start/end, data verification, reconciled job count, observed RPO/RTO, and defects found. The release cannot claim recovery readiness without this evidence.
