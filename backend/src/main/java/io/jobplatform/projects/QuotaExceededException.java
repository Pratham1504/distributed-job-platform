package io.jobplatform.projects;

public class QuotaExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public QuotaExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
