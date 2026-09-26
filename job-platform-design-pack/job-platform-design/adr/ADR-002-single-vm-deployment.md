# ADR-002: Single Azure VM for MVP Deployment

## Decision

Run API, scheduler, workers, PostgreSQL, RabbitMQ, Redis, Prometheus, and Grafana with Docker Compose on one Azure Linux VM.

## Alternatives considered

- AKS: strong learning value but unnecessary cost/operational complexity for MVP.
- Managed Azure broker/database/cache: easier operations but not appropriate for a free-first portfolio deployment.
- Multiple VMs: improves isolation but exceeds project scope and free-tier assumptions.

## Consequences

The VM is a single failure domain. The system cannot honestly claim high availability. Off-host backup and restore testing remain required for any public production claim.

