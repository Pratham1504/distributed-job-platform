package io.jobplatform.projects;

import java.time.Instant;
import java.util.UUID;
public record ProjectResponse(UUID id, String name, Instant createdAt) { }
