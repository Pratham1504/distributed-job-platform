package io.jobplatform.jobs;

public enum JobStatus {
    PENDING,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
    CANCELLED
}
