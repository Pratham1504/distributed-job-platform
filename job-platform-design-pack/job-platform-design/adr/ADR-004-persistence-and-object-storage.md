# ADR-004: Self-Hosted Core Runtime with Optional Azure Blob Storage

## Decision

Run PostgreSQL, RabbitMQ, Redis, API, scheduler, workers, and monitoring containers on the Azure VM. Use Azure Blob Storage only for result artifacts and verified off-host database backups when configured.

## Alternatives considered

- Self-host all artifacts/backups: leaves the system unable to recover from VM loss.
- Use managed PostgreSQL, RabbitMQ, Redis, or AKS: improves operational posture but conflicts with the affordable MVP constraint.

## Consequences

Blob Storage is an explicitly allowed managed dependency, not part of the self-hosted-core restriction. Its actual cost must be monitored; no document may imply it is always free. The platform must remain usable locally without Blob Storage, using a local artifact adapter.

