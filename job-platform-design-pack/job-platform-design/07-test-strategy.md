# Test Strategy and Release Criteria

## 1. Test layers

| Layer | What it proves | Runs in CI |
| --- | --- | --- |
| Unit | Validation, state machine, retry classification, handler logic | Every pull request |
| Repository/integration | PostgreSQL constraints, Flyway migrations, outbox locking | Every pull request using Testcontainers |
| Messaging integration | RabbitMQ publish confirms, redelivery, DLQ routing | Every pull request using Testcontainers |
| API contract | OpenAPI request/response/error conformance | Every pull request |
| End-to-end | Submit-to-completion, scheduled run, cancel, manual retry | Main branch and release candidate |
| Failure recovery | crash at commit/publish/execute/finalise boundaries | Nightly and release candidate |
| Load | SLO/capacity targets and fairness | Before public demo and release |
| Security | authorization isolation, SSRF, secret redaction, dependency scan | Every pull request where feasible; full scan nightly |

## 2. Mandatory automated scenarios

1. Identical idempotency key returns original job and does not create duplicate effects.
2. API crash after transaction commit still results in delivery from outbox reconciliation.
3. Publisher duplicate is ignored safely by consumer claim/deduplication.
4. Worker crash after side-effect simulation causes redelivery; stable simulated provider `effectId` prevents duplicate effect across automatic retries.
5. Retryable failure uses expected delays and terminal failure reaches DLQ.
6. Cancellation wins before execution; cancellation racing with worker claim follows documented result.
7. User B cannot read, cancel, or retry user A’s resources.
8. Expired attempt lease is detected, recovery fences the old lease token, and an old worker cannot finalise after recovery.
9. Broker-loss reconciliation redispatches a queued run whose outbox event had been marked published.
10. One-time job does not start before `scheduled_at` and starts within schedule objective under nominal load.
11. Migration from previous schema succeeds with preserved data.
12. `SEND_EMAIL` with a report payload is rejected at API validation; every job-type/payload variant validates only its own schema.
13. Refresh-token rotation revokes the parent; replay of the parent revokes the whole token family.
14. Final failure commits both the terminal state and `JOB_DEAD_LETTERED` outbox event; a later publisher outage delays but cannot erase DLQ publication.
15. Terminal `result_json` validates against the handler result schema and remains readable through the job API after worker restart.

## 3. Load and SLO tests

- Sustain 5 accepted jobs/sec for 5 minutes with p95 submission latency <= 500 ms.
- Burst 60 submissions in 60 seconds for one user; excess requests return `429`, not server errors.
- Submit mixed priorities and prove lower priority receives service under weighted dispatch.
- Run four concurrent 1-minute jobs and ensure a fifth remains queued without DB/broker failure.

## 4. Objective release criteria

- 100% unit, integration, and contract test pass rate.
- No known critical/high security finding without written risk acceptance.
- All mandatory failure scenarios pass.
- Capacity test meets targets or an exception is documented and approved.
- Restore drill meets RTO/RPO evidence requirements before production launch.
- Deployment rollback is rehearsed in a non-production environment.
