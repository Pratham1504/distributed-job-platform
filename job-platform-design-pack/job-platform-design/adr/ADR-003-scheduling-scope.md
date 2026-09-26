# ADR-003: Immediate and One-Time Scheduling in MVP

## Decision

MVP supports immediate jobs and one-time `scheduledAt` jobs in UTC. Recurring cron schedules, timezones, daylight-saving changes, missed runs, and overlap policies are deferred to R2.

## Alternatives considered

- Add cron now: creates unresolved behaviour for DST, schedule edits, and scheduler downtime.
- Use a third-party scheduler: reduces code but obscures the design learning objective.

## Consequences

The first release has simpler, testable scheduling semantics. R2 must define explicit misfire and overlap policy before cron is introduced.

