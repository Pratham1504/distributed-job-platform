package io.jobplatform.jobs;

import java.util.List;
import java.util.UUID;

public record JobRunResponse(UUID id, int runNumber, JobStatus status, int attemptCount, int maxAttempts,
                             List<ExecutionAttemptResponse> executions) { }
