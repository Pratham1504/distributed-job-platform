# Production-Readiness Checklist

This checklist must be completed before describing the service as production-ready. A portfolio demo may explicitly mark items as not applicable or not yet met.

## Core correctness

- [ ] All acceptance criteria in `01-product-requirements.md` pass.
- [ ] Outbox publisher and reconciliation have passed crash-boundary tests.
- [ ] At-least-once and idempotency limitations are documented in user-facing integration guide.
- [ ] State transition and cancellation race tests pass.

## Security

- [ ] Authentication, ownership, key rotation, webhook SSRF, and log-redaction tests pass.
- [ ] No known critical/high vulnerability remains without recorded risk acceptance.
- [ ] Secrets are not in repository, image, or public configuration.
- [ ] Public endpoints use TLS; internal services are not public.

## Capacity and reliability

- [ ] SLO capacity tests pass or exceptions are accepted.
- [ ] Queue fairness and per-project quota tests pass.
- [ ] Worker timeout, DLQ, and stale-worker recovery runbooks are tested.
- [ ] VM/disk capacity supports stated workload and retention.

## Operations and recovery

- [ ] Dashboards and alerts have an owner and have been tested.
- [ ] Off-host backups exist and a restore drill meets stated RPO/RTO.
- [ ] Deployment and rollback rehearsal completed.
- [ ] Known single-VM availability limitation is accepted in writing.

## Release evidence

- [ ] Version/tag, migration version, test report, load report, and restore-drill report linked in release note.
- [ ] Known limitations and remaining risks explicitly listed.
