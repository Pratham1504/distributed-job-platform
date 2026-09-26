package io.jobplatform.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateJobRequest(
        @NotNull JobType jobType,
        @NotNull JsonNode payload,
        @NotNull JobPriority priority,
        Instant scheduledAt,
        @Min(1) @Max(4) Integer maxAttempts,
        String webhookUrl
) { }
