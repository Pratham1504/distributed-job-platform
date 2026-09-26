package io.jobplatform.apikeys;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyMetadata(UUID id, String name, String prefix, String status, Instant createdAt, Instant lastUsedAt) { }
