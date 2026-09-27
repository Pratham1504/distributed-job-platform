package io.jobplatform.operations;

import io.jobplatform.artifacts.ArtifactStore;
import io.jobplatform.assets.FileAssetService;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the documented retention policy without deleting live work or unpublished outbox data. */
@Service
public class RetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final FileAssetService fileAssets;
    private final Duration jobsRetention;
    private final Duration attemptsRetention;
    private final Duration outboxRetention;
    private final Duration auditRetention;
    private final Duration artifactRetention;

    public RetentionCleanupService(JdbcTemplate jdbc, ArtifactStore artifacts, FileAssetService fileAssets,
                                   @Value("${app.retention.jobs:PT2160H}") Duration jobsRetention,
                                   @Value("${app.retention.attempts:PT720H}") Duration attemptsRetention,
                                   @Value("${app.retention.published-outbox:PT336H}") Duration outboxRetention,
                                   @Value("${app.retention.audit:PT4320H}") Duration auditRetention,
                                   @Value("${app.retention.artifacts:PT720H}") Duration artifactRetention) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.fileAssets = fileAssets;
        this.jobsRetention = jobsRetention;
        this.attemptsRetention = attemptsRetention;
        this.outboxRetention = outboxRetention;
        this.auditRetention = auditRetention;
        this.artifactRetention = artifactRetention;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void scheduledCleanup() {
        CleanupResult result = purgeExpired();
        if (!result.isEmpty()) {
            log.info("event=retention_cleanup jobs={} attempts={} outbox={} audit={} artifacts={} fileAssets={}", result.jobs(),
                    result.attempts(), result.outbox(), result.audit(), result.artifacts(), result.fileAssets());
        } else {
            log.debug("event=retention_cleanup jobs=0 attempts=0 outbox=0 audit=0 artifacts=0 fileAssets=0");
        }
    }

    @Transactional
    public CleanupResult purgeExpired() {
        Instant now = Instant.now();
        Timestamp jobCutoff = Timestamp.from(now.minus(jobsRetention));
        int attempts = jdbc.update("delete from execution_attempts where finished_at < ?", Timestamp.from(now.minus(attemptsRetention)));
        int outbox = jdbc.update("delete from outbox_events where published_at is not null and published_at < ?",
                Timestamp.from(now.minus(outboxRetention)));
        int audit = jdbc.update("delete from audit_events where created_at < ?", Timestamp.from(now.minus(auditRetention)));
        // Delete child rows first because the schema intentionally keeps foreign keys restrictive.
        jdbc.update("""
                delete from execution_attempts a using job_runs r, jobs j
                where a.run_id=r.id and r.job_id=j.id and j.status in ('COMPLETED','FAILED','CANCELLED')
                and j.completed_at < ?
                """, jobCutoff);
        jdbc.update("""
                delete from job_runs r using jobs j
                where r.job_id=j.id and j.status in ('COMPLETED','FAILED','CANCELLED') and j.completed_at < ?
                """, jobCutoff);
        int jobs = jdbc.update("""
                delete from jobs where status in ('COMPLETED','FAILED','CANCELLED') and completed_at < ?
                """, jobCutoff);
        Instant artifactCutoff = now.minus(artifactRetention);
        int artifacts = purgeArtifacts(artifactCutoff);
        int expiredFileAssets = fileAssets.purgeExpired();
        return new CleanupResult(jobs, attempts, outbox, audit, artifacts, expiredFileAssets);
    }

    private int purgeArtifacts(Instant cutoff) {
        try {
            return artifacts.purgeBefore(cutoff);
        } catch (IOException exception) {
            log.error("event=artifact_retention_cleanup_failed error={}", exception.getClass().getSimpleName());
            return 0;
        }
    }

    public record CleanupResult(int jobs, int attempts, int outbox, int audit, int artifacts, int fileAssets) {
        boolean isEmpty() { return jobs == 0 && attempts == 0 && outbox == 0 && audit == 0 && artifacts == 0 && fileAssets == 0; }
    }
}
