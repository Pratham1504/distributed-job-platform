package io.jobplatform.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ExecutionAttemptResponse(UUID id, int attemptNumber, String status, Instant startedAt, Instant finishedAt,
                                       String errorCode, JsonNode result, String resultRef) { }
