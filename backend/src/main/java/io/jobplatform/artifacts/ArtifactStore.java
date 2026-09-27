package io.jobplatform.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage boundary for generated results. Local development uses a mounted filesystem; a cloud
 * object-store adapter can implement the same contract without changing worker or API behavior.
 */
public interface ArtifactStore {
    String storeJson(UUID projectId, UUID artifactId, JsonNode content) throws IOException;

    Optional<StoredArtifact> findJson(UUID projectId, UUID artifactId) throws IOException;

    int purgeBefore(Instant cutoff) throws IOException;

    record StoredArtifact(Path path, long size) { }
}
