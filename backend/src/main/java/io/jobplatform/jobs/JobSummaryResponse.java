package io.jobplatform.jobs;

import java.time.Instant;
import java.util.UUID;

public record JobSummaryResponse(
        UUID id,
        UUID projectId,
        JobType jobType,
        JobStatus status,
        JobPriority priority,
        Instant scheduledAt,
        Instant createdAt
) { }
