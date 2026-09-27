package io.jobplatform.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import io.jobplatform.assets.FileAssetKind;
import io.jobplatform.assets.FileAssetMetadata;
import io.jobplatform.assets.FileAssetService;
import io.jobplatform.assets.FileAssetTooLargeException;
import io.jobplatform.artifacts.ArtifactStore;
import io.jobplatform.observability.PlatformMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acknowledges a broker delivery only after the current PostgreSQL lease has recorded a durable
 * terminal or retry state. Handler work is bounded and renews its lease while it is running.
 */
@Service
public class ReportWorker {
    private static final Logger log = LoggerFactory.getLogger(ReportWorker.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ArtifactStore artifacts;
    private final FileAssetService fileAssets;
    private final HandlerResultValidator resultValidator;
    private final PlatformMetrics metrics;
    private final TransactionTemplate transactions;
    private final Duration leaseDuration;
    private final Duration leaseRenewalInterval;
    private final Duration maximumExecutionDuration;
    private final Duration placeholderHandlerMinimumDuration;
    private final Duration placeholderHandlerMaximumDuration;
    private final int maximumConcurrentExecutions;
    private final int maximumConcurrentExecutionsPerProject;
    private final UUID workerId = UUID.randomUUID();
    private final UUID workerEpoch = UUID.randomUUID();
    private final ScheduledExecutorService leaseRenewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "job-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

    public ReportWorker(JdbcTemplate jdbc, ObjectMapper json, ArtifactStore artifacts, FileAssetService fileAssets,
                        HandlerResultValidator resultValidator, PlatformMetrics metrics,
                        PlatformTransactionManager transactionManager,
                        @Value("${app.worker.lease-duration:PT30S}") Duration leaseDuration,
                        @Value("${app.worker.lease-renewal-interval:PT10S}") Duration leaseRenewalInterval,
                        @Value("${app.worker.maximum-execution-duration:PT10M}") Duration maximumExecutionDuration,
                        @Value("${app.worker.placeholder-handler-minimum-duration:PT1S}") Duration placeholderHandlerMinimumDuration,
                        @Value("${app.worker.placeholder-handler-maximum-duration:PT2S}") Duration placeholderHandlerMaximumDuration,
                        @Value("${app.worker.maximum-concurrent-executions:4}") int maximumConcurrentExecutions,
                        @Value("${app.worker.maximum-concurrent-executions-per-project:2}") int maximumConcurrentExecutionsPerProject) {
        this.jdbc = jdbc;
        this.json = json;
        this.artifacts = artifacts;
        this.fileAssets = fileAssets;
        this.resultValidator = resultValidator;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leaseDuration = leaseDuration;
        this.leaseRenewalInterval = leaseRenewalInterval;
        this.maximumExecutionDuration = maximumExecutionDuration;
        this.placeholderHandlerMinimumDuration = placeholderHandlerMinimumDuration;
        this.placeholderHandlerMaximumDuration = placeholderHandlerMaximumDuration;
        this.maximumConcurrentExecutions = maximumConcurrentExecutions;
        this.maximumConcurrentExecutionsPerProject = maximumConcurrentExecutionsPerProject;
        if (leaseRenewalInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Worker lease renewal interval must be shorter than its duration.");
        }
        if (maximumConcurrentExecutions < 1 || maximumConcurrentExecutionsPerProject < 1) {
            throw new IllegalArgumentException("Worker execution limits must be positive.");
        }
        if (placeholderHandlerMinimumDuration.isNegative()
                || placeholderHandlerMaximumDuration.isNegative()
                || placeholderHandlerMinimumDuration.compareTo(placeholderHandlerMaximumDuration) > 0) {
            throw new IllegalArgumentException("Placeholder handler delay bounds must be non-negative and ordered.");
        }
    }

    @PostConstruct
    void register() {
        Instant now = Instant.now();
        jdbc.update("""
                insert into workers (id,instance_name,worker_epoch,last_heartbeat_at,started_at,status)
                values (?,?,?,?,?,?)
                """, workerId, "report-worker", workerEpoch, Timestamp.from(now), Timestamp.from(now), "HEALTHY");
        log.info("event=worker_registered workerId={} epoch={} leaseSeconds={} maximumExecutionSeconds={}",
                workerId, workerEpoch, leaseDuration.toSeconds(), maximumExecutionDuration.toSeconds());
    }

    @PreDestroy
    void stopExecutors() {
        leaseRenewals.shutdownNow();
        handlers.shutdownNow();
    }

    @Scheduled(fixedDelay = 10_000)
    void heartbeat() {
        jdbc.update("update workers set last_heartbeat_at=? where id=?", Timestamp.from(Instant.now()), workerId);
        log.debug("event=worker_heartbeat workerId={}", workerId);
    }

    /** Separate consumer pools implement the documented approximate 5:3:1 priority weighting. */
    @RabbitListener(queues = "jobs.high", concurrency = "${app.worker.high-concurrency:5}")
    public void consumeHigh(Message message, Channel channel) throws IOException { consume(message, channel); }

    @RabbitListener(queues = "jobs.default", concurrency = "${app.worker.default-concurrency:3}")
    public void consumeDefault(Message message, Channel channel) throws IOException { consume(message, channel); }

    @RabbitListener(queues = "jobs.low", concurrency = "${app.worker.low-concurrency:1}")
    public void consumeLow(Message message, Channel channel) throws IOException { consume(message, channel); }

    void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            JsonNode event = readReadyEvent(message);
            Claim claim = claim(event);
            if (claim == null) {
                channel.basicAck(deliveryTag, false);
                log.debug("event=job_delivery_ignored deliveryTag={} reason=stale_or_not_eligible", deliveryTag);
                return;
            }

            AtomicBoolean leaseLost = new AtomicBoolean(false);
            ScheduledFuture<?> renewal = leaseRenewals.scheduleAtFixedRate(
                    () -> renewOrMarkLost(claim, leaseLost),
                    leaseRenewalInterval.toMillis(), leaseRenewalInterval.toMillis(), TimeUnit.MILLISECONDS);
            try {
                ObjectNode result = executeWithinLimit(claim, event);
                if (leaseLost.get() || !finalizeSuccess(claim, result)) {
                    channel.basicNack(deliveryTag, false, true);
                    log.warn("event=job_delivery_requeued jobId={} runId={} reason=lease_not_current", claim.jobId(), claim.runId());
                    return;
                }
                channel.basicAck(deliveryTag, false);
            } catch (Exception exception) {
                if (leaseLost.get() || !finalizeFailure(claim, exception, isRetryable(exception))) {
                    channel.basicNack(deliveryTag, false, true);
                    log.warn("event=job_delivery_requeued jobId={} runId={} reason=lease_not_current error={}",
                            claim.jobId(), claim.runId(), exception.getClass().getSimpleName());
                    return;
                }
                channel.basicAck(deliveryTag, false);
            } finally {
                renewal.cancel(true);
            }
        } catch (InvalidReadyEventException exception) {
            channel.basicReject(deliveryTag, false);
            log.error("event=job_delivery_rejected deliveryTag={} reason=invalid_message detail={}",
                    deliveryTag, exception.getMessage());
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, true);
            log.error("event=job_delivery_requeued deliveryTag={} reason=unexpected_error", deliveryTag, exception);
        }
    }

    private JsonNode readReadyEvent(Message message) {
        try {
            JsonNode event = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            if (event == null || !event.isObject() || event.path("schemaVersion").asInt(-1) != 1) {
                throw new InvalidReadyEventException("Unsupported event schema.");
            }
            requireUuid(event, "jobId");
            requireUuid(event, "projectId");
            requireUuid(event, "runId");
            if (event.path("dispatchVersion").asInt(0) < 1 || event.path("jobType").asText().isBlank()) {
                throw new InvalidReadyEventException("Missing dispatch version or job type.");
            }
            return event;
        } catch (InvalidReadyEventException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidReadyEventException("Message body is not a valid JOB_READY event.");
        }
    }

    private void requireUuid(JsonNode event, String field) {
        try {
            UUID.fromString(event.path(field).asText());
        } catch (Exception exception) {
            throw new InvalidReadyEventException("Missing or invalid " + field + ".");
        }
    }

    private ObjectNode executeWithinLimit(Claim claim, JsonNode event) throws Exception {
        Future<ObjectNode> execution = handlers.submit(() -> executeHandler(claim, event));
        try {
            return execution.get(maximumExecutionDuration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            execution.cancel(true);
            throw new RetryableHandlerException("Handler exceeded the configured execution limit.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("Handler failed with an unrecoverable error.", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableHandlerException("Worker was interrupted while running a handler.", exception);
        }
    }

    private ObjectNode executeHandler(Claim claim, JsonNode event) throws IOException {
        ObjectNode result = json.createObjectNode();
        String jobType = event.path("jobType").asText();
        result.put("type", jobType);
        if (!"PROCESS_FILE".equals(jobType)) {
            simulatePlaceholderHandlerWork(jobType, claim);
        }
        switch (jobType) {
            case "GENERATE_REPORT" -> {
                ObjectNode report = json.createObjectNode();
                report.put("type", "GENERATE_REPORT");
                report.put("effectId", claim.effectId().toString());
                report.put("generatedAt", Instant.now().toString());
                report.put("message", "Local report artifact generated by the job platform.");
                result.put("artifactRef", artifacts.storeJson(claim.projectId(), claim.effectId(), report));
            }
            case "PROCESS_FILE" -> processFile(claim, result);
            case "SEND_EMAIL", "SEND_NOTIFICATION" -> result.put("providerMessageId", "local-" + claim.effectId());
            default -> throw new IllegalArgumentException("Unsupported job type: " + jobType);
        }
        return result;
    }

    /**
     * The report, email, and notification adapters are deliberately deterministic local stand-ins
     * until real providers are configured. Keep them visibly asynchronous so the dashboard and
     * worker lifecycle can be exercised without pretending that an external side effect occurred.
     */
    private void simulatePlaceholderHandlerWork(String jobType, Claim claim) {
        long minimumDelayMs = placeholderHandlerMinimumDuration.toMillis();
        long maximumDelayMs = placeholderHandlerMaximumDuration.toMillis();
        long delayMs = minimumDelayMs == maximumDelayMs
                ? minimumDelayMs
                : ThreadLocalRandom.current().nextLong(minimumDelayMs, maximumDelayMs + 1);
        log.debug("event=placeholder_handler_started jobId={} attemptId={} jobType={} simulatedDelayMs={}",
                claim.jobId(), claim.attemptId(), jobType, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableHandlerException("Placeholder handler was interrupted.", exception);
        }
        log.debug("event=placeholder_handler_completed jobId={} attemptId={} jobType={} simulatedDelayMs={}",
                claim.jobId(), claim.attemptId(), jobType, delayMs);
    }

    private void renewOrMarkLost(Claim claim, AtomicBoolean leaseLost) {
        try {
            if (!renewLease(claim) && leaseLost.compareAndSet(false, true)) {
                metrics.leaseLost();
                log.warn("event=lease_lost jobId={} runId={} attemptId={}", claim.jobId(), claim.runId(), claim.attemptId());
            }
        } catch (Exception exception) {
            log.warn("event=lease_renewal_failed jobId={} runId={} error={}",
                    claim.jobId(), claim.runId(), exception.getClass().getSimpleName());
        }
    }

    boolean renewLease(Claim claim) {
        Instant now = Instant.now();
        int renewed = jdbc.update("""
                update execution_attempts set lease_expires_at=?
                where id=? and lease_token=? and status='RUNNING' and lease_expires_at > ?
                """, Timestamp.from(now.plus(leaseDuration)), claim.attemptId(), claim.leaseToken(), Timestamp.from(now));
        if (renewed == 1) log.debug("event=lease_renewed jobId={} attemptId={}", claim.jobId(), claim.attemptId());
        return renewed == 1;
    }

    Claim claim(JsonNode event) {
        return transactions.execute(status -> claimInTransaction(event));
    }

    private Claim claimInTransaction(JsonNode event) {
        UUID runId = UUID.fromString(event.path("runId").asText());
        UUID jobId = UUID.fromString(event.path("jobId").asText());
        UUID projectId = UUID.fromString(event.path("projectId").asText());
        int dispatchVersion = event.path("dispatchVersion").asInt();
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", resultSet -> null, "job-platform:execution-capacity");
        Integer activeTotal = jdbc.queryForObject("select count(*) from execution_attempts where status='RUNNING'", Integer.class);
        Integer activeForProject = jdbc.queryForObject("""
                select count(*) from execution_attempts a
                join job_runs r on r.id = a.run_id join jobs j on j.id = r.job_id
                where a.status='RUNNING' and j.project_id = ?
                """, Integer.class, projectId);
        if ((activeTotal != null && activeTotal >= maximumConcurrentExecutions)
                || (activeForProject != null && activeForProject >= maximumConcurrentExecutionsPerProject)) {
            Instant deferredAt = Instant.now().plusSeconds(5);
            int deferred = jdbc.update("""
                    update job_runs r set status='RETRY_WAIT', next_dispatch_at=?
                    from jobs j where r.id=? and r.job_id=j.id and r.status='QUEUED'
                    and r.dispatch_version=? and j.id=? and j.status='QUEUED'
                    """, Timestamp.from(deferredAt), runId, dispatchVersion, jobId);
            if (deferred == 1) {
                jdbc.update("update jobs set status='RETRY_WAIT',updated_at=? where id=?", Timestamp.from(Instant.now()), jobId);
                metrics.capacityDeferred();
                log.info("event=job_deferred jobId={} runId={} reason=worker_capacity", jobId, runId);
            }
            return null;
        }
        int claimed = jdbc.update("""
                update job_runs r set status='RUNNING', attempt_count=attempt_count+1
                from jobs j where r.id=? and r.job_id=j.id and r.status='QUEUED'
                and r.dispatch_version=? and j.id=? and j.status='QUEUED' and j.cancel_requested_at is null
                """, runId, dispatchVersion, jobId);
        if (claimed != 1) return null;
        Run run = jdbc.query("""
                select r.effect_id,r.attempt_count,r.max_attempts,j.payload::text,p.owner_id
                from job_runs r join jobs j on j.id=r.job_id join projects p on p.id=j.project_id
                where r.id=?
                """, rs -> rs.next() ? new Run(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3),
                jsonNode(rs.getString(4)), rs.getObject(5, UUID.class)) : null, runId);
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into execution_attempts (id,run_id,attempt_number,worker_id,worker_epoch,lease_token,lease_expires_at,status,started_at,created_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, attemptId, runId, run.attemptCount(), workerId, workerEpoch, leaseToken,
                Timestamp.from(now.plus(leaseDuration)), "RUNNING", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("update jobs set status='RUNNING',updated_at=? where id=?", Timestamp.from(now), jobId);
        log.info("event=job_claimed jobId={} runId={} attemptId={} attempt={}",
                jobId, runId, attemptId, run.attemptCount());
        return new Claim(projectId, run.ownerId(), jobId, runId, attemptId, run.effectId(), leaseToken,
                run.attemptCount(), run.maxAttempts(), run.payload());
    }

    boolean finalizeSuccess(Claim claim, ObjectNode result) {
        resultValidator.validate(result);
        return transactions.execute(status -> {
            Instant now = Instant.now();
            String resultReference = result.path("artifactRef").isTextual() ? result.path("artifactRef").asText()
                    : result.path("cleanedAssetId").isTextual()
                    ? "/api/v1/projects/" + claim.projectId() + "/files/" + result.path("cleanedAssetId").asText() + "/download"
                    : null;
            int finalised = jdbc.update("""
                    update execution_attempts set status='COMPLETED', finished_at=?, result_json=cast(? as jsonb), result_ref=?, lease_expires_at=null
                    where id=? and lease_token=? and status='RUNNING' and lease_expires_at > ?
                    """, Timestamp.from(now), jsonString(result), resultReference, claim.attemptId(), claim.leaseToken(), Timestamp.from(now));
            if (finalised != 1) return false;
            jdbc.update("update job_runs set status='COMPLETED',completed_at=? where id=? and status='RUNNING'", Timestamp.from(now), claim.runId());
            jdbc.update("update jobs set status='COMPLETED',completed_at=?,updated_at=? where id=? and status='RUNNING'",
                    Timestamp.from(now), Timestamp.from(now), claim.jobId());
            metrics.jobCompleted();
            log.info("event=job_completed jobId={} runId={} attemptId={}", claim.jobId(), claim.runId(), claim.attemptId());
            return true;
        });
    }

    boolean finalizeFailure(Claim claim, Exception exception, boolean retryable) {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            String code = retryable ? "HANDLER_RETRYABLE_FAILURE" : "HANDLER_PERMANENT_FAILURE";
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage().substring(0, Math.min(512, exception.getMessage().length()));
            int finalised = jdbc.update("""
                    update execution_attempts set status=?, finished_at=?, error_code=?, error_message=?, lease_expires_at=null
                    where id=? and lease_token=? and status='RUNNING' and lease_expires_at > ?
                    """, retryable && claim.attemptNumber() < claim.maxAttempts() ? "RETRY_SCHEDULED" : "FAILED",
                    Timestamp.from(now), code, detail, claim.attemptId(), claim.leaseToken(), Timestamp.from(now));
            if (finalised != 1) return false;
            if (retryable && claim.attemptNumber() < claim.maxAttempts()) {
                int delaySeconds = switch (claim.attemptNumber()) { case 1 -> 10; case 2 -> 30; default -> 120; };
                jdbc.update("update job_runs set status='RETRY_WAIT',next_dispatch_at=? where id=? and status='RUNNING'",
                        Timestamp.from(now.plusSeconds(delaySeconds)), claim.runId());
                jdbc.update("update jobs set status='RETRY_WAIT',updated_at=? where id=? and status='RUNNING'",
                        Timestamp.from(now), claim.jobId());
                metrics.jobRetryScheduled();
                log.warn("event=job_retry_scheduled jobId={} runId={} attemptId={} delaySeconds={} errorCode={}",
                        claim.jobId(), claim.runId(), claim.attemptId(), delaySeconds, code);
                return true;
            }
            jdbc.update("update job_runs set status='FAILED',completed_at=? where id=? and status='RUNNING'", Timestamp.from(now), claim.runId());
            jdbc.update("update jobs set status='FAILED',completed_at=?,updated_at=? where id=? and status='RUNNING'",
                    Timestamp.from(now), Timestamp.from(now), claim.jobId());
            deadLetter(claim, code, now);
            metrics.jobFailed();
            log.error("event=job_failed jobId={} runId={} attemptId={} errorCode={}",
                    claim.jobId(), claim.runId(), claim.attemptId(), code);
            return true;
        });
    }

    private boolean isRetryable(Exception exception) {
        return !(exception instanceof IllegalArgumentException)
                && !(exception instanceof FileAssetTooLargeException)
                && !(exception instanceof InvalidReadyEventException);
    }

    private void deadLetter(Claim claim, String reason, Instant now) {
        UUID eventId = UUID.randomUUID();
        ObjectNode event = json.createObjectNode();
        event.put("schemaVersion", 1);
        event.put("eventId", eventId.toString());
        event.put("eventType", "JOB_DEAD_LETTERED");
        event.put("occurredAt", now.toString());
        event.put("jobId", claim.jobId().toString());
        event.put("runId", claim.runId().toString());
        event.put("reason", reason);
        event.put("correlationId", eventId.toString());
        try {
            jdbc.update("""
                    insert into outbox_events (id,aggregate_type,aggregate_id,event_type,payload,dedupe_key,created_at)
                    values (?,'JOB_RUN',?,'JOB_DEAD_LETTERED',cast(? as jsonb),?,?)
                    """, eventId, claim.runId(), json.writeValueAsString(event), claim.runId() + ":dead-letter", Timestamp.from(now));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not record terminal dead-letter event.", exception);
        }
    }

    private String jsonString(ObjectNode result) {
        try { return json.writeValueAsString(result); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialise handler result.", exception); }
    }

    private void processFile(Claim claim, ObjectNode result) throws IOException {
        UUID sourceAssetId;
        try {
            sourceAssetId = UUID.fromString(claim.payload().path("sourceAssetId").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PROCESS_FILE requires a valid sourceAssetId.");
        }
        String operation = claim.payload().path("operation").asText();
        FileAssetService.WorkerSource source = fileAssets.resolveSource(claim.projectId(), sourceAssetId);
        CsvProcessingResult processed = processCsv(source, operation);
        String basename = csvBasename(source.asset().filename());
        FileAssetMetadata cleaned = fileAssets.createGeneratedCsv(claim.projectId(), claim.ownerId(), claim.effectId(),
                FileAssetKind.CLEANED_CSV, "cleaned-" + basename + ".csv", processed.cleanedCsv(), processed.validRows());
        FileAssetMetadata errors = fileAssets.createGeneratedCsv(claim.projectId(), claim.ownerId(), claim.effectId(),
                FileAssetKind.ERROR_CSV, "errors-" + basename + ".csv", processed.errorCsv(), processed.rejectedRows());
        result.put("type", "PROCESS_FILE");
        result.put("sourceAssetId", source.asset().id().toString());
        result.put("cleanedAssetId", cleaned.id().toString());
        result.put("errorAssetId", errors.id().toString());
        result.put("totalRows", processed.totalRows());
        result.put("validRows", processed.validRows());
        result.put("rejectedRows", processed.rejectedRows());
        result.put("duplicatesRemoved", processed.duplicatesRemoved());
    }

    /** Performs the real, bounded CSV workflow and creates bytes for both authorised output assets. */
    private CsvProcessingResult processCsv(FileAssetService.WorkerSource source, String operation) throws IOException {
        boolean normalize = switch (operation) {
            case "CSV_VALIDATE" -> false;
            case "CSV_NORMALIZE" -> true;
            default -> throw new IllegalArgumentException("Unsupported file operation: " + operation);
        };
        CSVFormat inputFormat = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(false).build();
        try (var reader = new InputStreamReader(Files.newInputStream(source.path()), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = inputFormat.parse(reader)) {
            List<String> inputHeader = new ArrayList<>(parser.getHeaderMap().keySet());
            if (inputHeader.isEmpty()) throw new IllegalArgumentException("CSV file must include a header row.");
            List<String> cleanHeader = normalize ? normalizedHeaders(inputHeader) : inputHeader;
            StringWriter cleanedWriter = new StringWriter();
            StringWriter errorWriter = new StringWriter();
            try (CSVPrinter cleanedPrinter = new CSVPrinter(cleanedWriter, CSVFormat.RFC4180.builder().setHeader(cleanHeader.toArray(String[]::new)).build());
                 CSVPrinter errorPrinter = new CSVPrinter(errorWriter, CSVFormat.RFC4180.builder()
                         .setHeader(errorHeader(inputHeader).toArray(String[]::new)).build())) {
                int totalRows = 0;
                int validRows = 0;
                int rejectedRows = 0;
                int duplicatesRemoved = 0;
                int rowNumber = 0;
                Set<String> seenRows = new HashSet<>();
                try {
                    for (CSVRecord record : parser) {
                        rowNumber++;
                        totalRows++;
                        List<String> values = record.toList();
                        String rejection = rowRejection(values, inputHeader.size());
                        if (rejection != null) {
                            rejectedRows++;
                            writeError(errorPrinter, rowNumber, rejection, values, inputHeader.size());
                            continue;
                        }
                        List<String> output = normalize ? values.stream().map(this::normalizeValue).toList() : values;
                        String duplicateKey = duplicateKey(output);
                        if (normalize && !seenRows.add(duplicateKey)) {
                            duplicatesRemoved++;
                            writeError(errorPrinter, rowNumber, "DUPLICATE_REMOVED", values, inputHeader.size());
                            continue;
                        }
                        cleanedPrinter.printRecord(output);
                        validRows++;
                    }
                } catch (UncheckedIOException exception) {
                    totalRows++;
                    rejectedRows++;
                    writeError(errorPrinter, rowNumber + 1, "MALFORMED_CSV_RECORD", List.of(), inputHeader.size());
                }
                return new CsvProcessingResult(cleanedWriter.toString().getBytes(StandardCharsets.UTF_8),
                        errorWriter.toString().getBytes(StandardCharsets.UTF_8), totalRows, validRows, rejectedRows, duplicatesRemoved);
            }
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("CSV source is not valid UTF-8.", exception);
        }
    }

    private String rowRejection(List<String> values, int expectedWidth) {
        if (values.size() != expectedWidth) return "ROW_WIDTH_MISMATCH";
        if (values.stream().anyMatch(value -> value == null || value.trim().isEmpty())) return "EMPTY_REQUIRED_FIELD";
        return null;
    }

    private List<String> normalizedHeaders(List<String> inputHeader) {
        Set<String> used = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (int index = 0; index < inputHeader.size(); index++) {
            String base = inputHeader.get(index).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (base.isBlank()) base = "column_" + (index + 1);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate)) candidate = base + "_" + suffix++;
            normalized.add(candidate);
        }
        return normalized;
    }

    private List<String> errorHeader(List<String> inputHeader) {
        List<String> header = new ArrayList<>();
        header.add("row_number");
        header.add("reason");
        header.addAll(inputHeader);
        return header;
    }

    private void writeError(CSVPrinter printer, int rowNumber, String reason, List<String> values, int expectedWidth) throws IOException {
        List<String> record = new ArrayList<>(expectedWidth + 2);
        record.add(Integer.toString(rowNumber));
        record.add(reason);
        for (int index = 0; index < expectedWidth; index++) {
            record.add(index < values.size() ? values.get(index) : "");
        }
        printer.printRecord(record);
    }

    private String normalizeValue(String source) {
        String value = source == null ? "" : source.trim();
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ofPattern("M/d/uuuu"),
                DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return LocalDate.parse(value, formatter).toString();
            } catch (DateTimeParseException ignored) {
                // Not a date in a documented input form; preserve the normalized string value.
            }
        }
        return value;
    }

    private String duplicateKey(List<String> values) {
        return values.stream().map(value -> value.replace("\\", "\\\\").replace("\u0000", "\\0"))
                .collect(java.util.stream.Collectors.joining("\u0000"));
    }

    private String csvBasename(String filename) {
        int extension = filename.toLowerCase(Locale.ROOT).lastIndexOf(".csv");
        return extension > 0 ? filename.substring(0, extension) : filename;
    }

    private JsonNode jsonNode(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored job payload is not valid JSON.", exception);
        }
    }

    private record CsvProcessingResult(byte[] cleanedCsv, byte[] errorCsv, int totalRows, int validRows, int rejectedRows,
                                       int duplicatesRemoved) { }
    private record Run(UUID effectId, int attemptCount, int maxAttempts, JsonNode payload, UUID ownerId) { }
    record Claim(UUID projectId, UUID ownerId, UUID jobId, UUID runId, UUID attemptId, UUID effectId, UUID leaseToken,
                 int attemptNumber, int maxAttempts, JsonNode payload) { }
    private static class RetryableHandlerException extends RuntimeException {
        RetryableHandlerException(String message, Throwable cause) { super(message, cause); }
    }
    private static class InvalidReadyEventException extends RuntimeException {
        InvalidReadyEventException(String message) { super(message); }
    }
}
