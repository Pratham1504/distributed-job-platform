package io.jobplatform.observability;

import io.jobplatform.jobs.JobPriority;
import io.jobplatform.jobs.JobType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality product metrics. IDs, email addresses, payload data, and API-key material
 * are deliberately never used as metric tags.
 */
@Component
public class PlatformMetrics {
    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        Gauge.builder("job.platform.queued.jobs", jdbc, source -> count(source,
                "select count(*) from jobs where status in ('QUEUED','PENDING','RETRY_WAIT')"))
                .description("Jobs waiting to run, including future schedules and retry waits.").register(registry);
        Gauge.builder("job.platform.running.executions", jdbc, source -> count(source,
                "select count(*) from execution_attempts where status='RUNNING'"))
                .description("Execution attempts with a live running state.").register(registry);
        Gauge.builder("job.platform.unpublished.outbox.events", jdbc, source -> count(source,
                "select count(*) from outbox_events where published_at is null"))
                .description("Durable events not yet confirmed by RabbitMQ.").register(registry);
        Gauge.builder("job.platform.healthy.workers", jdbc, source -> count(source,
                "select count(*) from workers where status='HEALTHY' and last_heartbeat_at > current_timestamp - interval '60 seconds'"))
                .description("Workers with a heartbeat in the last minute.").register(registry);
        Gauge.builder("job.platform.oldest.unpublished.outbox.seconds", jdbc, source -> oldestOutboxAge(source))
                .description("Age of the oldest event awaiting broker confirmation.").register(registry);
    }

    public void jobAccepted(JobType type, JobPriority priority, boolean scheduled) {
        registry.counter("job.platform.job.submissions", "job_type", type.name(), "priority", priority.name(),
                "scheduled", Boolean.toString(scheduled)).increment();
    }

    public void submissionReplayed() { increment("job.platform.idempotency.replays"); }
    public void jobCancelled() { increment("job.platform.job.cancellations"); }
    public void manualRetry() { increment("job.platform.manual.retries"); }
    public void dispatch(JobPriority priority, String reason) {
        registry.counter("job.platform.job.dispatches", "priority", priority.name(), "reason", reason).increment();
    }
    public void outboxPublished(String eventType) {
        registry.counter("job.platform.outbox.publications", "event_type", eventType).increment();
    }
    public void outboxFailed(String eventType) {
        registry.counter("job.platform.outbox.failures", "event_type", eventType).increment();
    }
    public void capacityDeferred() { increment("job.platform.capacity.deferrals"); }
    public void jobCompleted() { increment("job.platform.job.completions"); }
    public void jobFailed() { increment("job.platform.job.failures"); }
    public void jobRetryScheduled() { increment("job.platform.job.retry.schedules"); }
    public void leaseExpired() { increment("job.platform.lease.expirations"); }
    public void leaseLost() { increment("job.platform.lease.losses"); }
    public void overloadRejected(String reason) {
        registry.counter("job.platform.admission.rejections", "reason", reason).increment();
    }

    private void increment(String metric) { registry.counter(metric).increment(); }

    private static double count(JdbcTemplate jdbc, String query) {
        try {
            Integer value = jdbc.queryForObject(query, Integer.class);
            return value == null ? 0 : value;
        } catch (DataAccessException ignored) {
            return Double.NaN;
        }
    }

    private static double oldestOutboxAge(JdbcTemplate jdbc) {
        try {
            Double seconds = jdbc.queryForObject("""
                    select coalesce(extract(epoch from current_timestamp - min(created_at)), 0)
                    from outbox_events where published_at is null
                    """, Double.class);
            return seconds == null ? 0 : Math.max(0, seconds);
        } catch (DataAccessException ignored) {
            return Double.NaN;
        }
    }
}
