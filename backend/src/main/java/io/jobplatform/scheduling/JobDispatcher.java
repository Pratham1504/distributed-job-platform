package io.jobplatform.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobplatform.jobs.JobPriority;
import io.jobplatform.jobs.JobType;
import io.jobplatform.observability.PlatformMetrics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class JobDispatcher {
    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean enabled;
    private final PlatformMetrics metrics;
    private final Duration reconciliationAge;

    public JobDispatcher(JdbcTemplate jdbc, ObjectMapper json, @Value("${app.dispatch.enabled:true}") boolean enabled,
                         PlatformMetrics metrics,
                         @Value("${app.dispatch.reconciliation-age:PT60S}") Duration reconciliationAge) {
        this.jdbc = jdbc;
        this.json = json;
        this.enabled = enabled;
        this.metrics = metrics;
        this.reconciliationAge = reconciliationAge;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.poll-interval-ms:1000}")
    @Transactional
    public void dispatchDue() { if (enabled) dispatchOne(); }

    @Transactional
    public void dispatchOne() {
        DueRun due = jdbc.query("""
                select r.id,j.id,j.project_id,r.effect_id,r.dispatch_version,j.job_type,j.priority
                from job_runs r join jobs j on j.id=r.job_id
                where (j.status='PENDING' and j.scheduled_at <= current_timestamp and r.status='PENDING')
                   or (j.status='RETRY_WAIT' and r.status='RETRY_WAIT' and r.next_dispatch_at <= current_timestamp)
                order by coalesce(r.next_dispatch_at,j.scheduled_at) for update of r,j skip locked limit 1
                """, rs -> rs.next() ? new DueRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5) + 1, JobType.valueOf(rs.getString(6)), JobPriority.valueOf(rs.getString(7))) : null);
        if (due == null) return;
        Instant now = Instant.now();
        jdbc.update("update job_runs set status='QUEUED',dispatch_version=?,next_dispatch_at=null where id=?", due.dispatchVersion(), due.runId());
        jdbc.update("update jobs set status='QUEUED',updated_at=? where id=?", Timestamp.from(now), due.jobId());
        UUID eventId = UUID.randomUUID(); ObjectNode event = json.createObjectNode();
        event.put("schemaVersion", 1); event.put("eventId", eventId.toString()); event.put("eventType", "JOB_READY"); event.put("occurredAt", now.toString());
        event.put("jobId", due.jobId().toString()); event.put("projectId", due.projectId().toString()); event.put("runId", due.runId().toString());
        event.put("effectId", due.effectId().toString()); event.put("jobType", due.jobType().name()); event.put("dispatchVersion", due.dispatchVersion());
        event.put("priority", due.priority().name()); event.put("correlationId", eventId.toString());
        try {
            jdbc.update("insert into outbox_events (id,aggregate_type,aggregate_id,event_type,payload,dedupe_key,created_at) values (?,'JOB_RUN',?,'JOB_READY',cast(? as jsonb),?,?)",
                    eventId, due.runId(), json.writeValueAsString(event), due.runId() + ":" + due.dispatchVersion(), Timestamp.from(now));
        } catch (Exception exception) { throw new IllegalStateException("Unable to create dispatch event", exception); }
        String reason = due.dispatchVersion() == 1 ? "schedule_due" : "retry_due";
        metrics.dispatch(due.priority(), reason);
        log.info("event=job_dispatched jobId={} runId={} priority={} reason={} dispatchVersion={}",
                due.jobId(), due.runId(), due.priority(), reason, due.dispatchVersion());
    }

    /**
     * Reissues a ready event only after its latest confirmed publish is old and no execution is
     * running. This covers broker/VM loss while the database remains the source of truth.
     */
    @Scheduled(fixedDelayString = "${app.dispatch.reconciliation-interval-ms:30000}")
    @Transactional
    public void reconcileQueuedRun() {
        if (enabled) reconcileOne();
    }

    @Transactional
    public void reconcileOne() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(reconciliationAge);
        DueRun candidate = jdbc.query("""
                select r.id,j.id,j.project_id,r.effect_id,r.dispatch_version + 1,j.job_type,j.priority
                from job_runs r join jobs j on j.id=r.job_id
                where r.status='QUEUED' and j.status='QUEUED'
                  and exists (select 1 from outbox_events o where o.aggregate_id=r.id and o.event_type='JOB_READY'
                              and o.published_at is not null and o.published_at < ?)
                  and not exists (select 1 from outbox_events o where o.aggregate_id=r.id and o.event_type='JOB_READY'
                                  and o.published_at is not null and o.published_at >= ?)
                  and not exists (select 1 from execution_attempts a where a.run_id=r.id and a.status='RUNNING'
                                  and a.lease_expires_at > current_timestamp)
                order by r.created_at for update of r,j skip locked limit 1
                """, rs -> rs.next() ? new DueRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5),
                JobType.valueOf(rs.getString(6)), JobPriority.valueOf(rs.getString(7))) : null,
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        if (candidate == null) return;
        int updated = jdbc.update("update job_runs set dispatch_version=? where id=? and status='QUEUED' and dispatch_version=?",
                candidate.dispatchVersion(), candidate.runId(), candidate.dispatchVersion() - 1);
        if (updated != 1) return;
        insertReadyEvent(candidate, now);
        metrics.dispatch(candidate.priority(), "reconciliation");
        log.warn("event=job_ready_reconciled jobId={} runId={} priority={} dispatchVersion={}", candidate.jobId(),
                candidate.runId(), candidate.priority(), candidate.dispatchVersion());
    }

    private void insertReadyEvent(DueRun due, Instant now) {
        UUID eventId = UUID.randomUUID(); ObjectNode event = json.createObjectNode();
        event.put("schemaVersion", 1); event.put("eventId", eventId.toString()); event.put("eventType", "JOB_READY"); event.put("occurredAt", now.toString());
        event.put("jobId", due.jobId().toString()); event.put("projectId", due.projectId().toString()); event.put("runId", due.runId().toString());
        event.put("effectId", due.effectId().toString()); event.put("jobType", due.jobType().name()); event.put("dispatchVersion", due.dispatchVersion());
        event.put("priority", due.priority().name()); event.put("correlationId", eventId.toString());
        try {
            jdbc.update("insert into outbox_events (id,aggregate_type,aggregate_id,event_type,payload,dedupe_key,created_at) values (?,'JOB_RUN',?,'JOB_READY',cast(? as jsonb),?,?)",
                    eventId, due.runId(), json.writeValueAsString(event), due.runId() + ":" + due.dispatchVersion(), Timestamp.from(now));
        } catch (Exception exception) { throw new IllegalStateException("Unable to create reconciled dispatch event", exception); }
    }
    private record DueRun(UUID runId, UUID jobId, UUID projectId, UUID effectId, int dispatchVersion, JobType jobType, JobPriority priority) { }
}
