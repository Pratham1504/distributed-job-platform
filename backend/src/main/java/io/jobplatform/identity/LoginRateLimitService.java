package io.jobplatform.identity;

import io.jobplatform.projects.QuotaExceededException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Limits credential guesses without putting an email address or client address into Redis keys.
 * The API intentionally returns the same generic 429 response for both dimensions.
 */
@Service
public class LoginRateLimitService {
    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int attemptsPerEmail;
    private final int attemptsPerAddress;
    private final Duration window;

    public LoginRateLimitService(StringRedisTemplate redis,
                                 @Value("${app.security.login-rate-limit.per-email:10}") int attemptsPerEmail,
                                 @Value("${app.security.login-rate-limit.per-address:30}") int attemptsPerAddress,
                                 @Value("${app.security.login-rate-limit.window:PT1M}") Duration window) {
        this.redis = redis;
        this.attemptsPerEmail = attemptsPerEmail;
        this.attemptsPerAddress = attemptsPerAddress;
        this.window = window;
    }

    public void reserveAttempt(String email, String remoteAddress) {
        check("email", fingerprint(email), attemptsPerEmail);
        check("address", fingerprint(remoteAddress == null ? "unknown" : remoteAddress), attemptsPerAddress);
    }

    private void check(String dimension, String fingerprint, int limit) {
        String key = "job-platform:login:" + dimension + ":" + fingerprint;
        try {
            Long count = redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(window.toSeconds()));
            if (count == null || count > limit) {
                Long ttl = redis.getExpire(key);
                throw new LoginRateLimitExceededException(ttl == null || ttl < 1 ? window.toSeconds() : ttl);
            }
        } catch (DataAccessException exception) {
            // Authentication must fail safely if its abuse-prevention dependency is unavailable.
            throw new IllegalStateException("Login rate-limit service is unavailable.", exception);
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect login attempts.", exception);
        }
    }

    public static class LoginRateLimitExceededException extends QuotaExceededException {
        public LoginRateLimitExceededException(long retryAfterSeconds) {
            super("Too many sign-in attempts. Try again after the retry period.", retryAfterSeconds);
        }
    }
}
