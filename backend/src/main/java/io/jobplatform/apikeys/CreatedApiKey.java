package io.jobplatform.apikeys;

import java.time.Instant;
import java.util.UUID;

/** The plaintext key is populated only by the create endpoint and must never be persisted or logged. */
public record CreatedApiKey(UUID id, String name, String prefix, String status, Instant createdAt, Instant lastUsedAt,
                            String plaintextKey) { }
