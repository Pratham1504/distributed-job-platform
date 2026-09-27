package io.jobplatform.assets;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable metadata for a file whose bytes live in private storage. */
public record FileAssetMetadata(
        UUID id,
        UUID projectId,
        FileAssetKind kind,
        String storageKey,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        Integer rowCount,
        List<String> header,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt) { }
