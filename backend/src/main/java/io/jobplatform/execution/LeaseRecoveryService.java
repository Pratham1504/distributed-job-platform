package io.jobplatform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobplatform.observability.PlatformMetrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LeaseRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformMetrics metrics;
    public LeaseRecoveryService(JdbcTemplate jdbc, ObjectMapper json, PlatformMetrics metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 15000)
    public void recoverExpired() { recoverOne(); }

    @Transactional
    public void recoverOne() {
        Expired expired = jdbc.query("""
                select a.id,a.run_id,r.job_id,r.attempt_count,r.max_attempts,j.project_id
                from execution_attempts a join job_runs r on r.id=a.run_id join jobs j on j.id=r.job_id
                where a.status='RUNNING' and a.lease_expires_at < current_timestamp
                order by a.lease_expires_at for update of a,r,j skip locked limit 1
                """, rs -> rs.next() ? new Expired(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getInt(5), rs.getObject(6, UUID.class)) : null);
        if (expired == null) return;
        Instant now = Instant.now();
        jdbc.update("update execution_attempts set status='ABANDONED',finished_at=?,error_code='LEASE_EXPIRED',error_message='Worker lease expired',lease_expires_at=null where id=? and status='RUNNING'",
                Timestamp.from(now), expired.attemptId());
        metrics.leaseExpired();
        if (expired.attemptCount() < expired.maxAttempts()) {
            int delay = switch (expired.attemptCount()) { case 1 -> 10; case 2 -> 30; default -> 120; };
            jdbc.update("update job_runs set status='RETRY_WAIT',next_dispatch_at=? where id=?", Timestamp.from(now.plusSeconds(delay)), expired.runId());
            jdbc.update("update jobs set status='RETRY_WAIT',updated_at=? where id=?", Timestamp.from(now), expired.jobId());
            metrics.jobRetryScheduled();
            log.warn("event=expired_lease_recovered jobId={} runId={} attemptId={} action=retry delaySeconds={}",
                    expired.jobId(), expired.runId(), expired.attemptId(), delay);
            return;
        }
        jdbc.update("update job_runs set status='FAILED',completed_at=? where id=?", Timestamp.from(now), expired.runId());
        jdbc.update("update jobs set status='FAILED',completed_at=?,updated_at=? where id=?", Timestamp.from(now), Timestamp.from(now), expired.jobId());
        UUID eventId = UUID.randomUUID(); ObjectNode event = json.createObjectNode();
        event.put("schemaVersion", 1); event.put("eventId", eventId.toString()); event.put("eventType", "JOB_DEAD_LETTERED");
        event.put("occurredAt", now.toString()); event.put("jobId", expired.jobId().toString()); event.put("runId", expired.runId().toString());
        event.put("reason", "LEASE_EXPIRED"); event.put("correlationId", eventId.toString());
        try {
            jdbc.update("insert into outbox_events (id,aggregate_type,aggregate_id,event_type,payload,dedupe_key,created_at) values (?,'JOB_RUN',?,'JOB_DEAD_LETTERED',cast(? as jsonb),?,?)",
                    eventId, expired.runId(), json.writeValueAsString(event), expired.runId() + ":dead-letter", Timestamp.from(now));
        } catch (Exception exception) { throw new IllegalStateException("Unable to record dead-letter event", exception); }
        metrics.jobFailed();
        log.error("event=expired_lease_recovered jobId={} runId={} attemptId={} action=dead_letter", expired.jobId(),
                expired.runId(), expired.attemptId());
    }
    private record Expired(UUID attemptId, UUID runId, UUID jobId, int attemptCount, int maxAttempts, UUID projectId) { }
}
