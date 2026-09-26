package io.jobplatform.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.jobplatform.projects.ProjectService;
import io.jobplatform.projects.ProjectQuotaService;
import io.jobplatform.security.ProductPrincipal;

@Service
public class JobService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JobRequestValidator requestValidator;
    private final ProjectService projects;
    private final ProjectQuotaService quotas;
    private final JobCursorCodec cursors;

    public JobService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, JobRequestValidator requestValidator,
                      ProjectService projects, ProjectQuotaService quotas, JobCursorCodec cursors) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.requestValidator = requestValidator;
        this.projects = projects;
        this.quotas = quotas;
        this.cursors = cursors;
    }

    @Transactional
    public JobAcceptedResponse submit(UUID projectId, ProductPrincipal principal, String idempotencyKey, CreateJobRequest request) {
        requestValidator.validate(idempotencyKey, request);
        requireJobAccess(projectId, principal);

        String payload = json(request.payload());
        String payloadHash = sha256(payload);
        ExistingJob existing = findByIdempotencyKey(projectId, idempotencyKey);
        if (existing != null) {
            if (!existing.payloadHash().equals(payloadHash)) {
                throw new JobConflictException("IDEMPOTENCY_KEY_REUSED", "The idempotency key was used with a different request.");
            }
            return new JobAcceptedResponse(existing.jobId(), existing.runId(), existing.status(), statusUrl(projectId, existing.jobId()));
        }
        quotas.reserveSubmission(projectId);

        Instant now = Instant.now();
        JobStatus status = request.scheduledAt() == null ? JobStatus.QUEUED : JobStatus.PENDING;
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID effectId = UUID.randomUUID();
        int dispatchVersion = request.scheduledAt() == null ? 1 : 0;
        int maxAttempts = request.maxAttempts() == null ? 4 : request.maxAttempts();

        try {
            jdbcTemplate.update("""
                    insert into jobs (id, project_id, job_type, payload, payload_hash, status, priority, scheduled_at, idempotency_key, created_at, updated_at)
                    values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)
                    """, jobId, projectId, request.jobType().name(), payload, payloadHash, status.name(), request.priority().name(),
                    timestamp(request.scheduledAt()), idempotencyKey, Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("""
                    insert into job_runs (id, job_id, run_number, effect_id, status, max_attempts, dispatch_version, next_dispatch_at, created_at)
                    values (?, ?, 1, ?, ?, ?, ?, ?, ?)
                    """, runId, jobId, effectId, status.name(), maxAttempts, dispatchVersion, timestamp(request.scheduledAt()), Timestamp.from(now));

            if (status == JobStatus.QUEUED) {
                insertJobReadyEvent(jobId, projectId, runId, effectId, dispatchVersion, request.jobType(), request.priority(), now);
            }
            jdbcTemplate.update("""
                    insert into audit_events (id, project_id, actor_type, event_type, resource_type, resource_id, metadata, created_at)
                    values (?, ?, 'SYSTEM', 'JOB_SUBMITTED', 'JOB', ?, cast(? as jsonb), ?)
                    """, UUID.randomUUID(), projectId, jobId, "{\"idempotencyKey\":\"" + idempotencyKey + "\"}", Timestamp.from(now));
        } catch (DataIntegrityViolationException duplicate) {
            ExistingJob racedJob = findByIdempotencyKey(projectId, idempotencyKey);
            if (racedJob != null && racedJob.payloadHash().equals(payloadHash)) {
                return new JobAcceptedResponse(racedJob.jobId(), racedJob.runId(), racedJob.status(), statusUrl(projectId, racedJob.jobId()));
            }
            throw duplicate;
        }
        return new JobAcceptedResponse(jobId, runId, status, statusUrl(projectId, jobId));
    }

    @Transactional(readOnly = true)
    public JobDetailsResponse get(UUID projectId, ProductPrincipal principal, UUID jobId) {
        requireJobAccess(projectId, principal);
        JobBase job = jdbcTemplate.query("""
                select id, project_id, job_type, status, priority, scheduled_at, created_at, completed_at
                from jobs where id = ? and project_id = ?
                """, rs -> rs.next()
                ? new JobBase(
                    rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    JobType.valueOf(rs.getString("job_type")), JobStatus.valueOf(rs.getString("status")),
                    JobPriority.valueOf(rs.getString("priority")), rs.getTimestamp("scheduled_at") == null ? null : rs.getTimestamp("scheduled_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant())
                : null, jobId, projectId);
        if (job == null) {
            throw new JobNotFoundException("Job not found.");
        }
        return details(job);
    }

    @Transactional(readOnly = true)
    public JobPageResponse list(UUID projectId, ProductPrincipal principal, String cursor, int limit, JobStatus status) {
        requireJobAccess(projectId, principal);
        if (limit < 1 || limit > 100) throw new JobValidationException("limit must be between 1 and 100.");
        JobCursorCodec.Cursor position = cursor == null || cursor.isBlank() ? null : cursors.decode(cursor);
        StringBuilder query = new StringBuilder("""
                select id, project_id, job_type, status, priority, scheduled_at, created_at, completed_at
                from jobs where project_id = ?
                """);
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(projectId);
        if (status != null) {
            query.append(" and status = ?");
            arguments.add(status.name());
        }
        if (position != null) {
            query.append(" and (created_at < ? or (created_at = ? and id < ?))");
            arguments.add(Timestamp.from(position.createdAt()));
            arguments.add(Timestamp.from(position.createdAt()));
            arguments.add(position.id());
        }
        query.append(" order by created_at desc, id desc limit ?");
        arguments.add(limit + 1);
        List<JobBase> fetched = jdbcTemplate.query(query.toString(), (rs, rowNumber) -> new JobBase(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), JobType.valueOf(rs.getString("job_type")),
                JobStatus.valueOf(rs.getString("status")), JobPriority.valueOf(rs.getString("priority")), instant(rs.getTimestamp("scheduled_at")),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("completed_at"))), arguments.toArray());
        boolean hasNext = fetched.size() > limit;
        if (hasNext) fetched = fetched.subList(0, limit);
        List<JobDetailsResponse> items = fetched.stream().map(this::details).toList();
        String nextCursor = hasNext ? cursors.encode(fetched.getLast().createdAt(), fetched.getLast().id()) : null;
        return new JobPageResponse(items, nextCursor);
    }

    @Transactional
    public JobDetailsResponse cancel(UUID projectId, ProductPrincipal principal, UUID jobId) {
        requireJobAccess(projectId, principal);
        JobDetailsResponse job = get(projectId, principal, jobId);
        if (job.status() == JobStatus.RUNNING) throw new JobConflictException("JOB_ALREADY_RUNNING", "A running job cannot be cancelled.");
        if (job.status() != JobStatus.PENDING && job.status() != JobStatus.QUEUED) {
            throw new JobConflictException("JOB_NOT_CANCELLABLE", "Only pending or queued jobs can be cancelled.");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("update jobs set status='CANCELLED',cancel_requested_at=?,completed_at=?,updated_at=? where id=? and project_id=?",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), jobId, projectId);
        jdbcTemplate.update("update job_runs set status='CANCELLED',completed_at=? where job_id=? and status in ('PENDING','QUEUED','RETRY_WAIT')",
                Timestamp.from(now), jobId);
        insertAuditEvent(projectId, principal.isApiKey() ? "API_KEY" : "USER", principal.isApiKey() ? principal.apiClientId() : principal.userId(), "JOB_CANCELLED", jobId, "{}", now);
        return get(projectId, principal, jobId);
    }

    @Transactional
    public JobAcceptedResponse manualRetry(UUID projectId, ProductPrincipal principal, UUID jobId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new JobValidationException("Idempotency-Key must be between 8 and 128 characters.");
        }
        requireJobAccess(projectId, principal);
        RetrySource lockedJob = jdbcTemplate.query("""
                select job_type, priority, status
                from jobs where id = ? and project_id = ? for update
                """, rs -> rs.next() ? new RetrySource(JobType.valueOf(rs.getString(1)), JobPriority.valueOf(rs.getString(2)),
                JobStatus.valueOf(rs.getString(3)), 0, 4) : null, jobId, projectId);
        if (lockedJob == null) throw new JobNotFoundException("Job not found.");

        ExistingJob existingRetry = jdbcTemplate.query("""
                select j.id, r.id, j.payload_hash, j.status
                from jobs j join job_runs r on r.job_id = j.id
                where j.id = ? and r.manual_retry_key = ?
                """, rs -> rs.next() ? new ExistingJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), JobStatus.valueOf(rs.getString(4))) : null, jobId, idempotencyKey);
        if (existingRetry != null) {
            return new JobAcceptedResponse(existingRetry.jobId(), existingRetry.runId(), existingRetry.status(), statusUrl(projectId, existingRetry.jobId()));
        }

        RetrySource source = jdbcTemplate.query("""
                select run_number, max_attempts from job_runs
                where job_id = ? order by run_number desc limit 1
                """, rs -> rs.next() ? new RetrySource(lockedJob.jobType(), lockedJob.priority(), lockedJob.status(), rs.getInt(1), rs.getInt(2)) : null,
                jobId);
        if (source == null) throw new IllegalStateException("A job must have at least one run.");
        if (source.status() != JobStatus.FAILED) throw new JobConflictException("JOB_NOT_FAILED", "Manual retry requires a failed job.");
        if (source.runNumber() >= 3) throw new JobConflictException("MANUAL_RETRY_LIMIT_REACHED", "A job may have at most two manual retry runs.");
        Instant now = Instant.now(); UUID runId = UUID.randomUUID(); UUID effectId = UUID.randomUUID(); int nextRun = source.runNumber() + 1;
        try {
            jdbcTemplate.update("""
                    insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,dispatch_version,next_dispatch_at,manual_retry_key,created_at)
                    values (?,?,?,?,'QUEUED',?,1,?,?,?)
                    """, runId, jobId, nextRun, effectId, source.maxAttempts(), Timestamp.from(now), idempotencyKey, Timestamp.from(now));
        } catch (DataIntegrityViolationException duplicate) {
            throw duplicate;
        }
        jdbcTemplate.update("update jobs set status='QUEUED',completed_at=null,updated_at=? where id=?", Timestamp.from(now), jobId);
        insertJobReadyEvent(jobId, projectId, runId, effectId, 1, source.jobType(), source.priority(), now);
        insertAuditEvent(projectId, principal.isApiKey() ? "API_KEY" : "USER", principal.isApiKey() ? principal.apiClientId() : principal.userId(), "JOB_MANUAL_RETRY_REQUESTED", jobId,
                "{\"runId\":\"" + runId + "\"}", now);
        return new JobAcceptedResponse(jobId, runId, JobStatus.QUEUED, statusUrl(projectId, jobId));
    }

    private void insertJobReadyEvent(UUID jobId, UUID projectId, UUID runId, UUID effectId, int dispatchVersion,
                                     JobType jobType, JobPriority priority, Instant now) {
        UUID eventId = UUID.randomUUID();
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schemaVersion", 1);
        event.put("eventId", eventId.toString());
        event.put("eventType", "JOB_READY");
        event.put("occurredAt", now.toString());
        event.put("jobId", jobId.toString());
        event.put("projectId", projectId.toString());
        event.put("runId", runId.toString());
        event.put("effectId", effectId.toString());
        event.put("jobType", jobType.name());
        event.put("dispatchVersion", dispatchVersion);
        event.put("priority", priority.name());
        event.put("correlationId", eventId.toString());
        jdbcTemplate.update("""
                insert into outbox_events (id, aggregate_type, aggregate_id, event_type, payload, dedupe_key, created_at)
                values (?, 'JOB_RUN', ?, 'JOB_READY', cast(? as jsonb), ?, ?)
                """, eventId, runId, json(event), runId + ":" + dispatchVersion, Timestamp.from(now));
    }

    private void insertAuditEvent(UUID projectId, String actorType, UUID actorId, String eventType, UUID jobId,
                                  String metadata, Instant now) {
        jdbcTemplate.update("""
                insert into audit_events (id, project_id, actor_type, actor_id, event_type, resource_type, resource_id, metadata, created_at)
                values (?, ?, ?, ?, ?, 'JOB', ?, cast(? as jsonb), ?)
                """, UUID.randomUUID(), projectId, actorType, actorId, eventType, jobId, metadata, Timestamp.from(now));
    }

    private JobDetailsResponse details(JobBase job) {
        List<JobRunResponse> runs = jdbcTemplate.query("""
                select id, run_number, status, attempt_count, max_attempts
                from job_runs where job_id = ? order by run_number asc
                """, (rs, rowNumber) -> {
            UUID runId = rs.getObject("id", UUID.class);
            return new JobRunResponse(runId, rs.getInt("run_number"), JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt_count"), rs.getInt("max_attempts"), attempts(runId));
        }, job.id());
        return new JobDetailsResponse(job.id(), job.projectId(), job.jobType(), job.status(), job.priority(), job.scheduledAt(),
                job.createdAt(), job.completedAt(), runs);
    }

    private List<ExecutionAttemptResponse> attempts(UUID runId) {
        return jdbcTemplate.query("""
                select id, attempt_number, status, started_at, finished_at, error_code, result_json, result_ref
                from execution_attempts where run_id = ? order by attempt_number asc
                """, (rs, rowNumber) -> new ExecutionAttemptResponse(rs.getObject("id", UUID.class), rs.getInt("attempt_number"),
                rs.getString("status"), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                rs.getString("error_code"), jsonNode(rs.getString("result_json")), rs.getString("result_ref")), runId);
    }

    private JsonNode jsonNode(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored execution result is not valid JSON.", exception);
        }
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private ExistingJob findByIdempotencyKey(UUID projectId, String key) {
        return jdbcTemplate.query("""
                select j.id as job_id, j.payload_hash, j.status, r.id as run_id
                from jobs j join job_runs r on r.job_id = j.id and r.run_number = 1
                where j.project_id = ? and j.idempotency_key = ?
                """, rs -> rs.next()
                ? new ExistingJob(rs.getObject("job_id", UUID.class), rs.getObject("run_id", UUID.class),
                    rs.getString("payload_hash"), JobStatus.valueOf(rs.getString("status"))) : null, projectId, key);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialise JSON.", exception);
        }
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private String statusUrl(UUID projectId, UUID jobId) {
        return "/api/v1/projects/" + projectId + "/jobs/" + jobId;
    }

    private void requireJobAccess(UUID projectId, ProductPrincipal principal) {
        if (principal.isApiKey()) {
            if (!projectId.equals(principal.apiKeyProjectId())) {
                throw new JobNotFoundException("Project not found.");
            }
            return;
        }
        projects.requireOwnership(projectId, principal.userId());
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private record ExistingJob(UUID jobId, UUID runId, String payloadHash, JobStatus status) { }
    private record RetrySource(JobType jobType, JobPriority priority, JobStatus status, int runNumber, int maxAttempts) { }
    private record JobBase(UUID id, UUID projectId, JobType jobType, JobStatus status, JobPriority priority,
                           Instant scheduledAt, Instant createdAt, Instant completedAt) { }
}
