# Job Platform Design Pack

This design pack is the source of truth before substantial implementation of the Distributed Job Scheduling Platform.

## Final architecture decisions

| Decision | Chosen approach | Why |
| --- | --- | --- |
| Ownership | Personal account owns one or more projects; jobs and API keys are project-scoped | Keeps authorization explicit; organization workspaces are R2 |
| Work accepted | Predefined, server-side handlers only | Never run arbitrary user code |
| Delivery guarantee | At-least-once processing | RabbitMQ and worker crashes can cause redelivery; handlers must be idempotent |
| Database-to-queue reliability | Transactional outbox in MVP | A committed job cannot be silently lost before it is published |
| Queue | RabbitMQ, durable queues and manual acknowledgement | Resource-efficient and appropriate for one-VM deployment |
| Scheduling | Immediate and one-time UTC schedules in MVP | Avoids under-specified cron/DST/misfire behavior; recurring schedules are R2 |
| Cancellation | Best effort before start; cannot interrupt a running handler in MVP | Clear and safe semantics |
| Deployment | Single Azure Linux VM with Docker Compose | Affordable demo topology; one failure domain, not HA |
| Blob storage | Optional off-host artifact/backup location | Azure Blob Storage is the only managed Azure dependency permitted; it is not assumed free at all volumes |

## Reading and implementation order

1. `01-product-requirements.md`
2. `02-nfr-and-slos.md`
3. `03-low-level-design.md`
4. `04-database-and-migrations.md`
5. `api/openapi.yaml` and `contracts/`
6. `06-security-threat-model.md`
7. `07-test-strategy.md`
8. Operational documents before public deployment

Do not implement beyond the initial end-to-end path until its automated tests pass:

```text
create job -> transaction commits job + outbox -> publisher routes message -> worker runs handler -> attempt is finalised -> client reads status
```

## Release boundaries

### MVP / demo release

- Personal accounts with one or more projects, JWT login, project-scoped API keys
- Immediate and one-time scheduled jobs
- Four predefined job types: report, email, file-processing, notification
- Priority queues, per-project concurrency limit, retry and dead-letter handling
- Transactional outbox, durable idempotency, audit events
- Docker Compose local deployment and single-VM Azure demonstration

### R2 / not part of MVP

- Organization workspaces and roles beyond personal ownership
- Recurring cron schedules, timezone/DST/misfire policies
- Multi-VM or Kubernetes high availability
- Arbitrary containerized jobs
- Paid managed Azure broker, database, cache, or Kubernetes services
