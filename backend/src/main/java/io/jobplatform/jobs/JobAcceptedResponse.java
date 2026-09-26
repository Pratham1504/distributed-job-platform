package io.jobplatform.jobs;

import java.util.UUID;

public record JobAcceptedResponse(UUID jobId, UUID runId, JobStatus status, String statusUrl) { }
