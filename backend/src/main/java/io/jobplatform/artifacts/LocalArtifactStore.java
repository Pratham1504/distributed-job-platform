package io.jobplatform.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Local, durable adapter used by the Compose stack and integration tests. */
@Service
public class LocalArtifactStore implements ArtifactStore {
    private final ObjectMapper json;
    private final Path root;

    public LocalArtifactStore(ObjectMapper json, @Value("${app.artifacts.local-root:./var/artifacts}") String root) {
        this.json = json;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String storeJson(UUID projectId, UUID artifactId, JsonNode content) throws IOException {
        Path directory = directory(projectId);
        Files.createDirectories(directory);
        Path target = directory.resolve(artifactId + ".json");
        Path temporary = Files.createTempFile(directory, artifactId + "-", ".tmp");
        try {
            json.writeValue(temporary.toFile(), content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return "/api/v1/projects/" + projectId + "/artifacts/" + artifactId;
    }

    @Override
    public Optional<StoredArtifact> findJson(UUID projectId, UUID artifactId) throws IOException {
        Path artifact = directory(projectId).resolve(artifactId + ".json");
        if (!Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        return Optional.of(new StoredArtifact(artifact, Files.size(artifact)));
    }

    @Override
    public int purgeBefore(java.time.Instant cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            int[] removed = {0};
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff) && Files.deleteIfExists(path)) {
                    removed[0]++;
                }
            }
            return removed[0];
        }
    }

    private Path directory(UUID projectId) {
        Path directory = root.resolve(projectId.toString()).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Invalid artifact location.");
        }
        return directory;
    }
}
