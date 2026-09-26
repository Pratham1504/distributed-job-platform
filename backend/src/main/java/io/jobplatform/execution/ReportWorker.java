package io.jobplatform.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportWorker {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final UUID workerId = UUID.randomUUID();
    private final UUID workerEpoch = UUID.randomUUID();

    public ReportWorker(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void register() {
        Instant now = Instant.now();
        jdbc.update("insert into workers (id,instance_name,worker_epoch,last_heartbeat_at,started_at,status) values (?,?,?,?,?,?)",
                workerId, "report-worker", workerEpoch, Timestamp.from(now), Timestamp.from(now), "HEALTHY");
    }

    @Scheduled(fixedDelay = 10000)
    void heartbeat() { jdbc.update("update workers set last_heartbeat_at=? where id=?", Timestamp.from(Instant.now()), workerId); }

    @RabbitListener(queues = {"jobs.high", "jobs.default", "jobs.low"}, concurrency = "${app.worker.concurrency:4}")
    public void consume(String body) throws Exception {
        JsonNode event = json.readTree(body);
        Claim claim = claim(event);
        if (claim == null) return;
        try {
            ObjectNode result = json.createObjectNode();
            String jobType = event.path("jobType").asText();
            result.put("type", jobType);
            switch (jobType) {
                case "GENERATE_REPORT" -> result.put("artifactRef", "reports/" + claim.effectId() + ".json");
                case "PROCESS_FILE" -> { result.put("artifactRef", "files/" + claim.effectId() + ".json"); result.put("recordCount", 0); }
                case "SEND_EMAIL", "SEND_NOTIFICATION" -> result.put("providerMessageId", "local-" + claim.effectId());
                default -> throw new IllegalArgumentException("Unsupported job type: " + jobType);
            }
            finalizeSuccess(claim, result);
        } catch (Exception exception) {
            finalizeFailure(claim, exception, !(exception instanceof IllegalArgumentException));
        }
    }

    Claim claim(JsonNode event) {
        return transactions.execute(status -> claimInTransaction(event));
    }

    private Claim claimInTransaction(JsonNode event) {
        UUID runId = UUID.fromString(event.path("runId").asText());
        UUID jobId = UUID.fromString(event.path("jobId").asText());
        UUID projectId = UUID.fromString(event.path("projectId").asText());
        int dispatchVersion = event.path("dispatchVersion").asInt();
        // This one transaction-scoped lock makes the capacity check and claim atomic across
        // all worker processes. Capacity pressure is deferred to the database scheduler,
        // never acknowledged and forgotten at the broker.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", resultSet -> null, "job-platform:execution-capacity");
        Integer activeTotal = jdbc.queryForObject("select count(*) from execution_attempts where status='RUNNING'", Integer.class);
        Integer activeForProject = jdbc.queryForObject("""
                select count(*) from execution_attempts a
                join job_runs r on r.id = a.run_id join jobs j on j.id = r.job_id
                where a.status='RUNNING' and j.project_id = ?
                """, Integer.class, projectId);
        if ((activeTotal != null && activeTotal >= 4) || (activeForProject != null && activeForProject >= 2)) {
            Instant deferredAt = Instant.now().plusSeconds(5);
            int deferred = jdbc.update("""
                    update job_runs r set status='RETRY_WAIT', next_dispatch_at=?
                    from jobs j where r.id=? and r.job_id=j.id and r.status='QUEUED'
                    and r.dispatch_version=? and j.id=? and j.status='QUEUED'
                    """, Timestamp.from(deferredAt), runId, dispatchVersion, jobId);
            if (deferred == 1) {
                jdbc.update("update jobs set status='RETRY_WAIT',updated_at=? where id=?", Timestamp.from(Instant.now()), jobId);
            }
            return null;
        }
        int claimed = jdbc.update("""
                update job_runs r set status='RUNNING', attempt_count=attempt_count+1
                from jobs j where r.id=? and r.job_id=j.id and r.status='QUEUED'
                and r.dispatch_version=? and j.id=? and j.status='QUEUED' and j.cancel_requested_at is null
                """, runId, dispatchVersion, jobId);
        if (claimed != 1) return null;
        Run run = jdbc.query("select effect_id,attempt_count,max_attempts from job_runs where id=?", rs -> rs.next()
                ? new Run(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)) : null, runId);
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into execution_attempts (id,run_id,attempt_number,worker_id,worker_epoch,lease_token,lease_expires_at,status,started_at,created_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, attemptId, runId, run.attemptCount(), workerId, workerEpoch, leaseToken, Timestamp.from(now.plusSeconds(30)), "RUNNING", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("update jobs set status='RUNNING',updated_at=? where id=?", Timestamp.from(now), jobId);
        return new Claim(jobId, runId, attemptId, run.effectId(), leaseToken, run.attemptCount(), run.maxAttempts());
    }

    void finalizeSuccess(Claim claim, ObjectNode result) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            int finalised = jdbc.update("""
                    update execution_attempts set status='COMPLETED', finished_at=?, result_json=cast(? as jsonb), lease_expires_at=null
                    where id=? and lease_token=? and status='RUNNING' and lease_expires_at > ?
                    """, Timestamp.from(now), jsonString(result), claim.attemptId(), claim.leaseToken(), Timestamp.from(now));
            if (finalised != 1) return;
            jdbc.update("update job_runs set status='COMPLETED',completed_at=? where id=? and status='RUNNING'", Timestamp.from(now), claim.runId());
            jdbc.update("update jobs set status='COMPLETED',completed_at=?,updated_at=? where id=? and status='RUNNING'", Timestamp.from(now), Timestamp.from(now), claim.jobId());
        });
    }

    void finalizeFailure(Claim claim, Exception exception, boolean retryable) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            String code = retryable ? "HANDLER_RETRYABLE_FAILURE" : "HANDLER_PERMANENT_FAILURE";
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage().substring(0, Math.min(512, exception.getMessage().length()));
            int finalised = jdbc.update("""
                    update execution_attempts set status=?, finished_at=?, error_code=?, error_message=?, lease_expires_at=null
                    where id=? and lease_token=? and status='RUNNING' and lease_expires_at > ?
                    """, retryable && claim.attemptNumber() < claim.maxAttempts() ? "RETRY_SCHEDULED" : "FAILED",
                    Timestamp.from(now), code, detail, claim.attemptId(), claim.leaseToken(), Timestamp.from(now));
            if (finalised != 1) return;
            if (retryable && claim.attemptNumber() < claim.maxAttempts()) {
                int delaySeconds = switch (claim.attemptNumber()) { case 1 -> 10; case 2 -> 30; default -> 120; };
                jdbc.update("update job_runs set status='RETRY_WAIT',next_dispatch_at=? where id=? and status='RUNNING'", Timestamp.from(now.plusSeconds(delaySeconds)), claim.runId());
                jdbc.update("update jobs set status='RETRY_WAIT',updated_at=? where id=? and status='RUNNING'", Timestamp.from(now), claim.jobId());
                return;
            }
            jdbc.update("update job_runs set status='FAILED',completed_at=? where id=? and status='RUNNING'", Timestamp.from(now), claim.runId());
            jdbc.update("update jobs set status='FAILED',completed_at=?,updated_at=? where id=? and status='RUNNING'", Timestamp.from(now), Timestamp.from(now), claim.jobId());
            deadLetter(claim, code, now);
        });
    }

    private void deadLetter(Claim claim, String reason, Instant now) {
        UUID eventId = UUID.randomUUID(); ObjectNode event = json.createObjectNode();
        event.put("schemaVersion", 1); event.put("eventId", eventId.toString()); event.put("eventType", "JOB_DEAD_LETTERED");
        event.put("occurredAt", now.toString()); event.put("jobId", claim.jobId().toString()); event.put("runId", claim.runId().toString());
        event.put("reason", reason); event.put("correlationId", eventId.toString());
        try {
            jdbc.update("insert into outbox_events (id,aggregate_type,aggregate_id,event_type,payload,dedupe_key,created_at) values (?,'JOB_RUN',?,'JOB_DEAD_LETTERED',cast(? as jsonb),?,?)",
                    eventId, claim.runId(), json.writeValueAsString(event), claim.runId() + ":dead-letter", Timestamp.from(now));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not record terminal dead-letter event.", exception);
        }
    }

    private String jsonString(ObjectNode result) {
        try { return json.writeValueAsString(result); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialise report result", exception); }
    }
    private record Run(UUID effectId, int attemptCount, int maxAttempts) { }
    record Claim(UUID jobId, UUID runId, UUID attemptId, UUID effectId, UUID leaseToken, int attemptNumber, int maxAttempts) { }
}
