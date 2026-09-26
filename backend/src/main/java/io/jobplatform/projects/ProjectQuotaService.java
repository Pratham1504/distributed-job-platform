package io.jobplatform.projects;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Redis enforces the short rate window; PostgreSQL provides the durable queue-depth guard. */
@Service
public class ProjectQuotaService {
    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final int perMinute;
    private final int maxQueued;

    public ProjectQuotaService(StringRedisTemplate redis, JdbcTemplate jdbc,
                               @Value("${app.quota.submissions-per-minute:60}") int perMinute,
                               @Value("${app.quota.max-queued-per-project:1000}") int maxQueued) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.perMinute = perMinute;
        this.maxQueued = maxQueued;
    }

    public void reserveSubmission(UUID projectId) {
        String key = "job-platform:quota:project:" + projectId + ":submissions";
        try {
            Long count = redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(Duration.ofMinutes(1).toSeconds()));
            if (count == null || count > perMinute) {
                Long ttl = redis.getExpire(key);
                throw new QuotaExceededException("Submission rate limit exceeded.", ttl == null || ttl < 1 ? 60 : ttl);
            }
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Submission quota service is unavailable.", exception);
        }

        // Serialize this project's capacity check with its job submissions. The advisory lock is
        // transaction-scoped because this service is invoked from JobService's transaction.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", resultSet -> null, projectId.toString());
        Integer queued = jdbc.queryForObject("select count(*) from jobs where project_id=? and status='QUEUED'", Integer.class, projectId);
        if (queued != null && queued >= maxQueued) {
            throw new QuotaExceededException("Project queued-job limit exceeded.", 60);
        }
    }
}
