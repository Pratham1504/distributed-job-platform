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
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = JobPlatformApplication.class, properties = {
        "app.dispatch.enabled=false",
        "app.worker.placeholder-handler-minimum-duration=PT0S",
        "app.worker.placeholder-handler-maximum-duration=PT0S",
        "app.quota.submissions-per-minute=2",
        "app.quota.max-waiting-jobs=1",
        "app.security.login-rate-limit.per-email=2",
        "app.security.login-rate-limit.per-address=30",
        "app.security.operator-emails=operator@example.test",
        "management.defaults.metrics.export.enabled=true",
        "management.prometheus.metrics.export.enabled=true"
})
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
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void identicalSubmissionIsIdempotentAndAChangedBodyIsRejected() throws Exception {
        Owner owner = owner();
        CreateJobRequest original = reportRequest(null);

        var first = jobs.submit(owner.projectId(), user(owner.userId()), "idempotency-flow-001", original);
        var replay = jobs.submit(owner.projectId(), user(owner.userId()), "idempotency-flow-001", original);

        assertEquals(first.jobId(), replay.jobId());
        assertEquals(1, jdbc.queryForObject("select count(*) from jobs where project_id=?", Integer.class, owner.projectId()));
        assertThrows(io.jobplatform.jobs.JobConflictException.class, () -> jobs.submit(owner.projectId(), user(owner.userId()),
                "idempotency-flow-001", new CreateJobRequest(JobType.GENERATE_REPORT,
                        json.readTree("{\"template\":\"JOB_AUDIT\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                        JobPriority.DEFAULT, null, 2, null)));
    }

    @Test
    void lowPriorityAdmissionIsShedWhenTheGlobalQueueLimitIsReached() throws Exception {
        Owner owner = owner(); Instant now = Instant.now();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,?,cast(? as jsonb),?,'QUEUED','DEFAULT',?,?,?)",
                UUID.randomUUID(), owner.projectId(), "GENERATE_REPORT", "{}", "global-capacity", "existing-waiting-job", Timestamp.from(now), Timestamp.from(now));

        assertThrows(QuotaExceededException.class, () -> jobs.submit(owner.projectId(), user(owner.userId()), "low-overload-001",
                new CreateJobRequest(JobType.GENERATE_REPORT,
                        json.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                        JobPriority.LOW, null, 1, null)));
    }

    @Test
    void loginIsRateLimitedAndRejectedAttemptsAreAudited() throws Exception {
        String email = "rate-" + UUID.randomUUID() + "@example.test";
        String request = "{\"email\":\"" + email + "\",\"password\":\"not-the-right-password\"}";

        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(request))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));

        assertEquals(2, jdbc.queryForObject("select count(*) from audit_events where event_type='AUTH_LOGIN_REJECTED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where event_type='AUTH_LOGIN_RATE_LIMITED'", Integer.class));
    }

    @Test
    void refreshRotationFencesAReplayedParentAndRevokesItsWholeFamily() throws Exception {
        String email = "rotation-" + UUID.randomUUID() + "@example.test";
        String registration = "{\"projectName\":\"Rotation project\",\"email\":\"" + email
                + "\",\"password\":\"correct-horse-battery-staple\"}";
        String parent = json.readTree(mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(registration))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("refreshToken").asText();
        String child = json.readTree(mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + parent + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("refreshToken").asText();

        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"" + parent + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"" + child + "\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(2, jdbc.queryForObject("select count(*) from refresh_tokens where user_id=(select id from users where email=?) and revoked_at is not null", Integer.class, email));
        assertEquals(2, jdbc.queryForObject("select count(*) from audit_events where event_type='AUTH_REFRESH_REJECTED'", Integer.class));
    }

    @Test
    void configuredOperatorCanUseOperatorHealthEndpointWithoutBroadProjectAccess() throws Exception {
        String registration = "{\"projectName\":\"Operator project\",\"email\":\"operator@example.test\",\"password\":\"correct-horse-battery-staple\"}";
        String accessToken = json.readTree(mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(registration))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();

        mvc.perform(get("/api/v1/operator/workers").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

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
        String artifactRef = json.readTree(result).path("artifactRef").asText();
        assertEquals(artifactRef, jdbc.queryForObject("select result_ref from execution_attempts where run_id=?", String.class, accepted.runId()));
        mvc.perform(get(artifactRef).header("Authorization", "Bearer " + tokens.create(user(userId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effectId").isNotEmpty())
                .andExpect(jsonPath("$.type").value("GENERATE_REPORT"));
        var detail = jobs.get(projectId, user(userId), accepted.jobId());
        assertEquals(1, detail.runs().size());
        assertEquals("COMPLETED", detail.runs().getFirst().status().name());
        assertEquals("GENERATE_REPORT", detail.runs().getFirst().executions().getFirst().result().path("type").asText());
    }

    @Test
    void projectCsvAssetProducesRealValidationOutputsAndAuthorisedDownloads() throws Exception {
        Owner owner = owner();
        MockMultipartFile source = new MockMultipartFile("file", "orders.csv", "text/csv",
                "order_id,amount\nA-100,42\nA-101,\nA-102,84\n".getBytes());
        String upload = mvc.perform(multipart("/api/v1/projects/{projectId}/files", owner.projectId())
                        .file(source).header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.kind").value("SOURCE_CSV"))
                .andExpect(jsonPath("$.header[0]").value("order_id"))
                .andReturn().getResponse().getContentAsString();
        String sourceAssetId = json.readTree(upload).path("id").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/files?kind=SOURCE_CSV", owner.projectId())
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sourceAssetId))
                .andExpect(jsonPath("$[0].filename").value("orders.csv"));

        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "csv-flow-001", new CreateJobRequest(
                JobType.PROCESS_FILE, json.readTree("{\"sourceAssetId\":\"" + sourceAssetId
                        + "\",\"operation\":\"CSV_VALIDATE\"}"), JobPriority.DEFAULT, null, 1, null));
        for (int i = 0; i < 8; i++) publisher.publishAvailable();

        waitFor(() -> "COMPLETED".equals(jdbc.queryForObject("select status from jobs where id=?", String.class, accepted.jobId())));
        String result = jdbc.queryForObject("select result_json::text from execution_attempts where run_id=?", String.class, accepted.runId());
        assertEquals("PROCESS_FILE", json.readTree(result).path("type").asText());
        assertEquals(3, json.readTree(result).path("totalRows").asInt());
        assertEquals(2, json.readTree(result).path("validRows").asInt());
        assertEquals(1, json.readTree(result).path("rejectedRows").asInt());
        String cleanedAssetId = json.readTree(result).path("cleanedAssetId").asText();
        String errorAssetId = json.readTree(result).path("errorAssetId").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/download", owner.projectId(), cleanedAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("A-100,42")));
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/download", owner.projectId(), errorAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("EMPTY_REQUIRED_FIELD")));
    }

    @Test
    void normalizingCsvRemovesDuplicatesAndRejectsCrossProjectAssetUse() throws Exception {
        Owner owner = owner(); Owner other = owner();
        MockMultipartFile source = new MockMultipartFile("file", "customers.csv", "text/csv",
                " Name ,Date\n Ada ,9/27/2026\nAda,2026-09-27\nGrace,27-Sep-2026\n".getBytes());
        String assetId = json.readTree(mvc.perform(multipart("/api/v1/projects/{projectId}/files", owner.projectId())
                        .file(source).header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}", other.projectId(), assetId)
                        .header("Authorization", "Bearer " + tokens.create(user(other.userId()))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/projects/{projectId}/jobs", other.projectId())
                        .header("Authorization", "Bearer " + tokens.create(user(other.userId())))
                        .header("Idempotency-Key", "foreign-file-001")
                        .contentType("application/json")
                        .content("{\"jobType\":\"PROCESS_FILE\",\"payload\":{\"sourceAssetId\":\"" + assetId
                                + "\",\"operation\":\"CSV_NORMALIZE\"},\"priority\":\"DEFAULT\"}"))
                .andExpect(status().isNotFound());

        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "normalize-flow-001", new CreateJobRequest(
                JobType.PROCESS_FILE, json.readTree("{\"sourceAssetId\":\"" + assetId
                        + "\",\"operation\":\"CSV_NORMALIZE\"}"), JobPriority.DEFAULT, null, 1, null));
        for (int i = 0; i < 8; i++) publisher.publishAvailable();
        waitFor(() -> "COMPLETED".equals(jdbc.queryForObject("select status from jobs where id=?", String.class, accepted.jobId())));
        String result = jdbc.queryForObject("select result_json::text from execution_attempts where run_id=?", String.class, accepted.runId());
        assertEquals(3, json.readTree(result).path("totalRows").asInt());
        assertEquals(2, json.readTree(result).path("validRows").asInt());
        assertEquals(1, json.readTree(result).path("duplicatesRemoved").asInt());
        String cleanedAssetId = json.readTree(result).path("cleanedAssetId").asText();
        String errorAssetId = json.readTree(result).path("errorAssetId").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/download", owner.projectId(), cleanedAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name,date")))
                .andExpect(content().string(containsString("Ada,2026-09-27")));
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/preview", owner.projectId(), cleanedAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header[0]").value("name"))
                .andExpect(jsonPath("$.rows[0][0]").value("Ada"));
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/preview?reason=DUPLICATE_REMOVED", owner.projectId(), errorAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header[1]").value("reason"))
                .andExpect(jsonPath("$.rows[0][1]").value("DUPLICATE_REMOVED"));
        mvc.perform(get("/api/v1/projects/{projectId}/files/{assetId}/preview", other.projectId(), errorAssetId)
                        .header("Authorization", "Bearer " + tokens.create(user(other.userId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonCsvAndOversizedSourceUploads() throws Exception {
        Owner owner = owner();
        mvc.perform(multipart("/api/v1/projects/{projectId}/files", owner.projectId())
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isBadRequest());

        byte[] oversized = new byte[(10 * 1024 * 1024) + 1];
        mvc.perform(multipart("/api/v1/projects/{projectId}/files", owner.projectId())
                        .file(new MockMultipartFile("file", "too-big.csv", "text/csv", oversized))
                        .header("Authorization", "Bearer " + tokens.create(user(owner.userId()))))
                .andExpect(status().isPayloadTooLarge());
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

        worker.finalizeFailure(new ReportWorker.Claim(owner.projectId(), owner.userId(), jobId, runId, attemptId, effectId, lease, 1, 2,
                json.createObjectNode()), new RuntimeException("temporary provider error"), true);

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
    void prometheusEndpointIsPublicAndIncludesProductMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("job_platform_queued_jobs")));
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
    void reconciliationReissuesAReadyEventAfterBrokerLossWithoutChangingItsEffectIdentity() throws Exception {
        Owner owner = owner();
        var accepted = jobs.submit(owner.projectId(), user(owner.userId()), "reconcile-flow-001", reportRequest(null));
        UUID effectId = jdbc.queryForObject("select effect_id from job_runs where id=?", UUID.class, accepted.runId());
        jdbc.update("update outbox_events set published_at=current_timestamp - interval '2 minutes' where aggregate_id=?", accepted.runId());

        dispatcher.reconcileOne();

        assertEquals(2, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?", Integer.class, accepted.runId()));
        assertEquals(2, jdbc.queryForObject("select dispatch_version from job_runs where id=?", Integer.class, accepted.runId()));
        assertEquals(effectId, jdbc.queryForObject("select effect_id from job_runs where id=?", UUID.class, accepted.runId()));
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

    @Test
    void exhaustedLeaseRecoveryPublishesTheDeadLetterEventToTheRealRabbitQueue() throws Exception {
        Owner owner = owner(); Instant now = Instant.now(); UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID attemptId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','RUNNING','DEFAULT','dead-letter-routing-001',?,?)", jobId, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'RUNNING',1,1,1,?)", runId, jobId, UUID.randomUUID(), Timestamp.from(now));
        jdbc.update("insert into execution_attempts (id,run_id,attempt_number,lease_token,lease_expires_at,status,created_at) values (?,?,1,?,?, 'RUNNING',?)", attemptId, runId, UUID.randomUUID(), Timestamp.from(now.minusSeconds(1)), Timestamp.from(now));

        recovery.recoverOne();
        UUID eventId = jdbc.queryForObject("select id from outbox_events where aggregate_id=? and event_type='JOB_DEAD_LETTERED'", UUID.class, runId);
        publishUntil(eventId);
        assertNotNull(jdbc.queryForObject("select published_at from outbox_events where id=?", Timestamp.class, eventId));

        Long depth = rabbitTemplate.execute(channel -> channel.messageCount("jobs.dead-letter"));
        assertNotNull(depth);
        assertTrue(depth > 0, "The broker DLQ queue must receive the durable dead-letter event.");
        Message delivered = receiveDeadLetterFor(runId);
        assertNotNull(delivered);
        assertEquals("JOB_DEAD_LETTERED", json.readTree(delivered.getBody()).path("eventType").asText());
        assertEquals(runId.toString(), json.readTree(delivered.getBody()).path("runId").asText());
        assertEquals("LEASE_EXPIRED", json.readTree(delivered.getBody()).path("reason").asText());
    }

    @Test
    void workerCrashAfterClaimIsRecoveredAndTheStaleWorkerCannotFinalise() throws Exception {
        Owner owner = owner(); Instant now = Instant.now(); UUID jobId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID effectId = UUID.randomUUID();
        jdbc.update("insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at) values (?,?,'GENERATE_REPORT','{}','x','QUEUED','DEFAULT','crash-boundary-001',?,?)", jobId, owner.projectId(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into job_runs (id,job_id,run_number,effect_id,status,max_attempts,attempt_count,dispatch_version,created_at) values (?,?,1,?,'QUEUED',2,0,1,?)", runId, jobId, effectId, Timestamp.from(now));
        var ready = json.readTree("{\"schemaVersion\":1,\"projectId\":\"" + owner.projectId() + "\",\"jobId\":\"" + jobId
                + "\",\"runId\":\"" + runId + "\",\"dispatchVersion\":1,\"jobType\":\"GENERATE_REPORT\"}");

        ReportWorker.Claim claim = worker.claim(ready);
        assertNotNull(claim);
        // No finalisation follows: this is the durable state a process crash leaves behind.
        jdbc.update("update execution_attempts set lease_expires_at=current_timestamp - interval '1 second' where id=?", claim.attemptId());
        recovery.recoverOne();

        assertEquals("ABANDONED", jdbc.queryForObject("select status from execution_attempts where id=?", String.class, claim.attemptId()));
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from job_runs where id=?", String.class, runId));
        assertEquals("RETRY_WAIT", jdbc.queryForObject("select status from jobs where id=?", String.class, jobId));
        var staleResult = json.createObjectNode().put("type", "GENERATE_REPORT").put("artifactRef", "/stale-worker-result");
        assertFalse(worker.finalizeSuccess(claim, staleResult), "A worker that crashed and lost its lease cannot overwrite recovery state.");

        jdbc.update("update job_runs set next_dispatch_at=current_timestamp - interval '1 second' where id=?", runId);
        dispatcher.dispatchOne();
        assertEquals("QUEUED", jdbc.queryForObject("select status from job_runs where id=?", String.class, runId));
        assertEquals(2, jdbc.queryForObject("select dispatch_version from job_runs where id=?", Integer.class, runId));
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

    private void publishUntil(UUID eventId) {
        for (int attempt = 0; attempt < 80; attempt++) {
            publisher.publishAvailable();
            Timestamp published = jdbc.queryForObject("select published_at from outbox_events where id=?", Timestamp.class, eventId);
            if (published != null) return;
        }
        throw new AssertionError("The dead-letter outbox event was not confirmed by RabbitMQ.");
    }

    private Message receiveDeadLetterFor(UUID expectedRunId) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Message message = rabbitTemplate.receive("jobs.dead-letter", 1_000);
            if (message == null) continue;
            if (expectedRunId.toString().equals(json.readTree(message.getBody()).path("runId").asText())) return message;
        }
        return null;
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
