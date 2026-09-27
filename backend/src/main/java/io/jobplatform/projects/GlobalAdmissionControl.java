package io.jobplatform.projects;

import io.jobplatform.jobs.JobPriority;
import io.jobplatform.observability.PlatformMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Protects the single-node MVP before a growing queue or local disk can affect accepted work.
 * Only LOW priority is shed first, exactly as the product overload policy specifies.
 */
@Service
public class GlobalAdmissionControl {
    private final JdbcTemplate jdbc;
    private final PlatformMetrics metrics;
    private final int maxWaitingJobs;
    private final int maxDiskUsedPercent;
    private final Path monitoredStoragePath;

    public GlobalAdmissionControl(JdbcTemplate jdbc, PlatformMetrics metrics, MeterRegistry registry,
                                  @Value("${app.quota.max-waiting-jobs:5000}") int maxWaitingJobs,
                                  @Value("${app.quota.max-local-storage-used-percent:80}") int maxDiskUsedPercent,
                                  @Value("${app.file-assets.local-root:./var/file-assets}") String storageRoot) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxWaitingJobs = maxWaitingJobs;
        this.maxDiskUsedPercent = maxDiskUsedPercent;
        this.monitoredStoragePath = Path.of(storageRoot);
        Gauge.builder("job.platform.global.waiting.jobs", this, GlobalAdmissionControl::waitingJobs)
                .description("All waiting jobs across projects, including schedules and retry waits.").register(registry);
        Gauge.builder("job.platform.local.storage.used.percent", this, GlobalAdmissionControl::diskUsedPercent)
                .description("Used percentage of the local volume holding CSV source and output assets.").register(registry);
    }

    public void requireAdmission(JobPriority priority) {
        if (priority != JobPriority.LOW) return;

        // Serialises the low-priority decision with other low-priority submissions in the current
        // transaction. High/default work remains available during an overload event.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", resultSet -> null,
                "job-platform:low-priority-admission");
        int waiting = waitingJobs();
        if (waiting >= maxWaitingJobs) {
            reject("GLOBAL_QUEUE_DEPTH", "Low-priority work is paused while the platform queue is busy.");
        }
        if (diskUsedPercent() >= maxDiskUsedPercent) {
            reject("LOCAL_STORAGE_PRESSURE", "Low-priority work is paused while local storage is under pressure.");
        }
    }

    public int waitingJobs() {
        Integer value = jdbc.queryForObject("select count(*) from jobs where status in ('PENDING','QUEUED','RETRY_WAIT')",
                Integer.class);
        return value == null ? 0 : value;
    }

    public double diskUsedPercent() {
        try {
            Files.createDirectories(monitoredStoragePath);
            FileStore store = Files.getFileStore(monitoredStoragePath);
            long total = store.getTotalSpace();
            if (total <= 0) return 0;
            return Math.max(0, Math.min(100, ((total - store.getUsableSpace()) * 100.0) / total));
        } catch (IOException exception) {
            // We cannot establish storage pressure. Shed optional low-priority work rather than
            // risk accepting it onto a volume that might already be unusable.
            return 100;
        }
    }

    private void reject(String reason, String message) {
        metrics.overloadRejected(reason);
        throw new QuotaExceededException(message, 60);
    }
}
