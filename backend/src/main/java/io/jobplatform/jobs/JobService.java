package io.jobplatform.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.jobplatform.projects.ProjectService;

@Service
public class JobService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JobRequestValidator requestValidator;
    private final ProjectService projects;

    public JobService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, JobRequestValidator requestValidator, ProjectService projects) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.requestValidator = requestValidator;
        this.projects = projects;
    }

    @Transactional
    public JobAcceptedResponse submit(UUID projectId, UUID userId, String idempotencyKey, CreateJobRequest request) {
        requestValidator.validate(idempotencyKey, request);
        projects.requireOwnership(projectId, userId);

        String payload = json(request.payload());
        String payloadHash = sha256(payload);
        ExistingJob existing = findByIdempotencyKey(projectId, idempotencyKey);
        if (existing != null) {
            if (!existing.payloadHash().equals(payloadHash)) {
                throw new JobConflictException("IDEMPOTENCY_KEY_REUSED", "The idempotency key was used with a different request.");
            }
            return new JobAcceptedResponse(existing.jobId(), existing.runId(), existing.status(), statusUrl(projectId, existing.jobId()));
        }

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
                    request.scheduledAt(), idempotencyKey, now, now);
            jdbcTemplate.update("""
                    insert into job_runs (id, job_id, run_number, effect_id, status, max_attempts, dispatch_version, next_dispatch_at, created_at)
                    values (?, ?, 1, ?, ?, ?, ?, ?, ?)
                    """, runId, jobId, effectId, status.name(), maxAttempts, dispatchVersion, request.scheduledAt(), now);

            if (status == JobStatus.QUEUED) {
                insertJobReadyEvent(jobId, projectId, runId, effectId, dispatchVersion, request.jobType(), request.priority(), now);
            }
            jdbcTemplate.update("""
                    insert into audit_events (id, project_id, actor_type, event_type, resource_type, resource_id, metadata, created_at)
                    values (?, ?, 'SYSTEM', 'JOB_SUBMITTED', 'JOB', ?, cast(? as jsonb), ?)
                    """, UUID.randomUUID(), projectId, jobId, "{\"idempotencyKey\":\"" + idempotencyKey + "\"}", now);
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
    public JobSummaryResponse get(UUID projectId, UUID userId, UUID jobId) {
        projects.requireOwnership(projectId, userId);
        JobSummaryResponse job = jdbcTemplate.query("""
                select id, project_id, job_type, status, priority, scheduled_at, created_at
                from jobs where id = ? and project_id = ?
                """, rs -> rs.next()
                ? new JobSummaryResponse(
                    rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    JobType.valueOf(rs.getString("job_type")), JobStatus.valueOf(rs.getString("status")),
                    JobPriority.valueOf(rs.getString("priority")), rs.getTimestamp("scheduled_at") == null ? null : rs.getTimestamp("scheduled_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant())
                : null, jobId, projectId);
        if (job == null) {
            throw new JobNotFoundException("Job not found.");
        }
        return job;
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
                """, eventId, runId, json(event), runId + ":" + dispatchVersion, now);
    }

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

    private record ExistingJob(UUID jobId, UUID runId, String payloadHash, JobStatus status) { }
}
