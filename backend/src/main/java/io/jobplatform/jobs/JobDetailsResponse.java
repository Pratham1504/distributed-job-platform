package io.jobplatform.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JobDetailsResponse(UUID id, UUID projectId, JobType jobType, JobStatus status, JobPriority priority,
                                 Instant scheduledAt, Instant createdAt, Instant completedAt,
                                 List<JobRunResponse> runs) { }
