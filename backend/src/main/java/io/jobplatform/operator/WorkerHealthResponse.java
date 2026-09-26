package io.jobplatform.operator;

import java.time.Instant;
import java.util.UUID;

public record WorkerHealthResponse(UUID id, String instanceName, String status, Instant lastHeartbeatAt) { }
