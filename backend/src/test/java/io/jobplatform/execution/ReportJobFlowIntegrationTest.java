package io.jobplatform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobplatform.JobPlatformApplication;
import io.jobplatform.apikeys.ApiKeyService;
import io.jobplatform.apikeys.CreateApiKeyRequest;
import io.jobplatform.jobs.CreateJobRequest;
import io.jobplatform.jobs.JobPriority;
import io.jobplatform.jobs.JobService;
import io.jobplatform.jobs.JobType;
import io.jobplatform.messaging.OutboxPublisher;
import io.jobplatform.scheduling.JobDispatcher;
import io.jobplatform.security.ProductPrincipal;
import io.jobplatform.security.JwtTokenService;
import io.jobplatform.projects.QuotaExceededException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = JobPlatformApplication.class, properties = {"app.dispatch.enabled=false", "app.quota.submissions-per-minute=2"})
@AutoConfigureMockMvc
class ReportJobFlowIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("job_platform").withUsername("job_platform").withPassword("test-password");
    @Container static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JobService jobs;
    @Autowired OutboxPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JobDispatcher dispatcher;
    @Autowired LeaseRecoveryService recovery;
    @Autowired ReportWorker worker;
    @Autowired ApiKeyService apiKeys;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;

    @Test
    void immediateReportJobFlowsFromOutboxToCompletedAttempt() throws Exception {
        Owner owner = owner(); UUID userId = owner.userId(); UUID projectId = owner.projectId();

        var accepted = jobs.submit(projectId, user(userId), "report-flow-001", new CreateJobRequest(JobType.GENERATE_REPORT,
                json.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                JobPriority.DEFAULT, null, 1, null));
        for (int i = 0; i < 8; i++) publisher.publishAvailable();

        waitFor(() -> "COMPLETED".equals(jdbc.queryForObject("select status from jobs where id=?", String.class, accepted.jobId())));
        assertEquals("COMPLETED", jdbc.queryForObject("select status from job_runs where id=?", String.class, accepted.runId()));
        String result = jdbc.queryForObject("select result_json::text from execution_attempts where run_id=?", String.class, accepted.runId());
        assertNotNull(result);
        assertEquals("GENERATE_REPORT", json.readTree(result).path("type").asText());
        var detail = jobs.get(projectId, user(userId), accepted.jobId());
        assertEquals(1, detail.runs().size());
        assertEquals("COMPLETED", detail.runs().getFirst().status().name());
        assertEquals("GENERATE_REPORT", detail.runs().getFirst().executions().getFirst().result().path("type").asText());
    }

    @Test
    void cancellationWinsBeforeTheOutboxMessageIsPublished() throws Exception {
        Owner owner = owner();
        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "cancel-flow-001", reportRequest(null));
        var cancelled = jobs.cancel(owner.projectId(), user(owner.userId()), accepted.jobId());
        assertEquals("CANCELLED", cancelled.status().name());
        assertEquals("CANCELLED", jdbc.queryForObject("select status from job_runs where id=?", String.class, accepted.runId()));
    }

    @Test
    void manualRetryCreatesOneNewRunWithANewEffectAndIsDurablyIdempotent() throws Exception {
        Owner owner = owner();
        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "manual-retry-flow-001", reportRequest(null));
        jdbc.update("update jobs set status='FAILED', completed_at=current_timestamp where id=?", accepted.jobId());
        jdbc.update("update job_runs set status='FAILED', completed_at=current_timestamp where id=?", accepted.runId());
        UUID originalEffectId = jdbc.queryForObject("select effect_id from job_runs where id=?", UUID.class, accepted.runId());

        var retried = jobs.manualRetry(owner.projectId(), user(owner.userId()), accepted.jobId(), "manual-retry-key-001");
        var replayed = jobs.manualRetry(owner.projectId(), user(owner.userId()), accepted.jobId(), "manual-retry-key-001");

        assertEquals(retried.runId(), replayed.runId());
        assertEquals("QUEUED", jdbc.queryForObject("select status from jobs where id=?", String.class, accepted.jobId()));
        assertEquals(2, jdbc.queryForObject("select run_number from job_runs where id=?", Integer.class, retried.runId()));
        assertEquals(2, jdbc.queryForObject("select max_attempts from job_runs where id=?", Integer.class, retried.runId()));
        assertNotNull(jdbc.queryForObject("select effect_id from job_runs where id=? and effect_id <> ?", UUID.class, retried.runId(), originalEffectId));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where resource_id=? and event_type='JOB_MANUAL_RETRY_REQUESTED'", Integer.class, accepted.jobId()));
    }

    @Test
    void apiKeyCanSubmitOnlyToItsProjectAndRevocationTakesEffectImmediately() throws Exception {
        Owner owner = owner();
        var key = apiKeys.create(owner.projectId(), owner.userId(), new CreateApiKeyRequest("integration-client"));

        mvc.perform(post("/api/v1/projects/{projectId}/jobs", owner.projectId())
                        .header("X-API-Key", key.plaintextKey()).header("Idempotency-Key", "api-key-submit-001")
                        .contentType("application/json").content(reportJson()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").isNotEmpty());

        Owner other = owner();
        mvc.perform(post("/api/v1/projects/{projectId}/jobs", other.projectId())
                        .header("X-API-Key", key.plaintextKey()).header("Idempotency-Key", "api-key-submit-002")
                        .contentType("application/json").content(reportJson()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/{projectId}/api-keys", owner.projectId()).header("X-API-Key", key.plaintextKey()))
                .andExpect(status().isForbidden());

        apiKeys.revoke(key.id(), owner.userId());
        mvc.perform(post("/api/v1/projects/{projectId}/jobs", owner.projectId())
                        .header("X-API-Key", key.plaintextKey()).header("Idempotency-Key", "api-key-submit-003")
                        .contentType("application/json").content(reportJson()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void jobListUsesAStableSignedCursor() throws Exception {
        Owner owner = owner();
        jobs.submit(owner.projectId(), user(owner.userId()), "list-cursor-001", reportRequest(null));
        jobs.submit(owner.projectId(), user(owner.userId()), "list-cursor-002", reportRequest(null));

        var first = jobs.list(owner.projectId(), user(owner.userId()), null, 1, null);
        var second = jobs.list(owner.projectId(), user(owner.userId()), first.nextCursor(), 1, null);

        assertEquals(1, first.items().size());
        assertEquals(1, second.items().size());
        assertNotNull(first.nextCursor());
        assertNotNull(second.items().getFirst().runs());
        assertEquals(false, first.items().getFirst().id().equals(second.items().getFirst().id()));
    }

    @Test
    void redisProjectQuotaRejectsTheThirdSubmissionInItsWindow() throws Exception {
        Owner owner = owner();
        jobs.submit(owner.projectId(), user(owner.userId()), "quota-flow-001", reportRequest(null));
        jobs.submit(owner.projectId(), user(owner.userId()), "quota-flow-002", reportRequest(null));
        assertThrows(QuotaExceededException.class,
                () -> jobs.submit(owner.projectId(), user(owner.userId()), "quota-flow-003", reportRequest(null)));
    }

    @Test
    void workerDefersAQueuedRunWhenTheProjectAlreadyHasTwoActiveExecutions() throws Exception {
        Owner owner = owner(); Instant now = Instant.now();
        runningJob(owner.projectId(), "capacity-running-001", now);
        runningJob(owner.projectId(), "capacity-running-002", now);
        UUID queuedJob = UUID.randomUUID(); UUID queuedRun = UUID.randomUUID(); UUID effectId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','QUEUED','DEFAULT','capacity-queued-001',?,?)", queuedJob, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,dispatch_version,created_at) values (?,?,1,?,'QUEUED',1,1,?)", queuedRun, queuedJob, effectId, Timestamp.from(now));

        var claim = worker.claim(json.readTree("{\"projectId\":\"" + owner.projectId() + "\",\"jobId\":\"" + queuedJob + "\",\"runId\":\"" + queuedRun + "\",\"dispatchVersion\":1}"));

        assertEquals(null, claim);
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from jobs where id=?", String.class, queuedJob));
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from job_runs where id=?", String.class, queuedRun));
        jdbc.update("update execution_attempts set status='ABANDONED' where run_id in (select id from job_runs where job_id in (select id from jobs where project_id=? and id<>?))", owner.projectId(), queuedJob);
    }

    @Test
    void retryableHandlerFailureSchedulesTheSameRunForDurableRedispatch() {
        Owner owner = owner(); Instant now = Instant.now(); UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID attemptId = UUID.randomUUID(); UUID effectId = UUID.randomUUID(); UUID lease = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','RUNNING','DEFAULT','handler-retry-001',?,?)", jobId, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'RUNNING',2,1,1,?)", runId, jobId, effectId, Timestamp.from(now));
        jdbc.update("insert into execution_attempts (id,run_id,attempt_number,lease_token,lease_expires_at,status,started_at,created_at) values (?,?,1,?,?,'RUNNING',?,?)", attemptId, runId, lease, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), Timestamp.from(now));

        worker.finalizeFailure(new ReportWorker.Claim(jobId, runId, attemptId, effectId, lease, 1, 2), new RuntimeException("temporary provider error"), true);

        assertEquals("RETRY_SCHEDULED", jdbc.queryForObject("select status from execution_attempts where id=?", String.class, attemptId));
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from job_runs where id=?", String.class, runId));
        jdbc.update("update job_runs set next_dispatch_at=current_timestamp - interval '1 second' where id=?", runId);
        dispatcher.dispatchOne();
        assertEquals("QUEUED", jdbc.queryForObject("select status from job_runs where id=?", String.class, runId));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?", Integer.class, runId));
    }

    @Test
    void operatorCanReadWorkerHealthWithoutCustomerJobPayloads() throws Exception {
        String operatorToken = tokens.create(new ProductPrincipal(UUID.randomUUID(), "operator@example.test", "OPERATOR"));
        mvc.perform(get("/api/v1/operator/workers").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].instanceName").value("report-worker"))
                .andExpect(jsonPath("$[0].status").value("HEALTHY"));
    }

    @Test
    void scheduledJobCreatesASecondDurableDispatchWhenDue() throws Exception {
        Owner owner = owner();
        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "scheduled-flow-001", reportRequest(Instant.now().plusSeconds(11)));
        jdbc.update("update jobs set scheduled_at=current_timestamp - interval '1 second' where id=?", accepted.jobId());
        dispatcher.dispatchOne();
        assertEquals("QUEUED", jdbc.queryForObject("select status from jobs where id=?", String.class, accepted.jobId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?", Integer.class, accepted.runId()));
    }

    @Test
    void expiredLeaseMovesTheRunToRetryWait() {
        Owner owner = owner(); Instant now = Instant.now(); UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID attemptId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','RUNNING','DEFAULT','recovery-flow-001',?,?)", jobId, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'RUNNING',2,1,1,?)", runId, jobId, UUID.randomUUID(), Timestamp.from(now));
        jdbc.update("insert into execution_attempts (id,run_id,attempt_number,lease_token,lease_expires_at,status,created_at) values (?,?,1,?,?, 'RUNNING',?)", attemptId, runId, UUID.randomUUID(), Timestamp.from(now.minusSeconds(1)), Timestamp.from(now));
        recovery.recoverOne();
        assertEquals("ABANDONED", jdbc.queryForObject("select status from execution_attempts where id=?", String.class, attemptId));
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from job_runs where id=?", String.class, runId));
    }

    @Test
    void exhaustedLeaseRecoveryPersistsTerminalFailureAndDeadLetterOutboxEvent() {
        Owner owner = owner(); Instant now = Instant.now(); UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID attemptId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','RUNNING','DEFAULT','dead-letter-flow-001',?,?)", jobId, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'RUNNING',1,1,1,?)", runId, jobId, UUID.randomUUID(), Timestamp.from(now));
        jdbc.update("insert into execution_attempts (id,run_id,attempt_number,lease_token,lease_expires_at,status,created_at) values (?,?,1,?,?, 'RUNNING',?)", attemptId, runId, UUID.randomUUID(), Timestamp.from(now.minusSeconds(1)), Timestamp.from(now));
        recovery.recoverOne();
        assertEquals("FAILED", jdbc.queryForObject("select status from jobs where id=?", String.class, jobId));
        assertEquals("JOB_DEAD_LETTERED", jdbc.queryForObject("select event_type from outbox_events where aggregate_id=?", String.class, runId));
    }

    private CreateJobRequest reportRequest(Instant scheduledAt) throws Exception {
        return new CreateJobRequest(JobType.GENERATE_REPORT, json.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"), JobPriority.DEFAULT, scheduledAt, 2, null);
    }
    private String reportJson() {
        return "{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"},\"priority\":\"DEFAULT\"}";
    }
    private Owner owner() {
        UUID userId = UUID.randomUUID(); UUID projectId = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.update("insert into users (id,email,password_hash,role,created_at) values (?,?,?,?,?)", userId, userId + "@example.test", "unused", "USER", Timestamp.from(now));
        jdbc.update("insert into projects (id,owner_id,name,status,created_at) values (?,?,?,?,?)", projectId, userId, "Project-" + projectId, "ACTIVE", Timestamp.from(now));
        return new Owner(userId, projectId);
    }

    private ProductPrincipal user(UUID userId) {
        return new ProductPrincipal(userId, userId + "@example.test", "USER");
    }
    private UUID runningJob(UUID projectId, String key, Instant now) {
        UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','RUNNING','DEFAULT',?,?,?)", jobId, projectId, key, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'RUNNING',1,1,1,?)", runId, jobId, UUID.randomUUID(), Timestamp.from(now));
        jdbc.update("insert into execution_attempts (id,run_id,attempt_number,status,started_at,created_at) values (?,?,1,'RUNNING',?,?)", UUID.randomUUID(), runId, Timestamp.from(now), Timestamp.from(now));
        return jobId;
    }

    private void waitFor(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.matches()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not complete within 10 seconds");
    }

    @FunctionalInterface private interface Check { boolean matches(); }
    private record Owner(UUID userId, UUID projectId) { }
}
