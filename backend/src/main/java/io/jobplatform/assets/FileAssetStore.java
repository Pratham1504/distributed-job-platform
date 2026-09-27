package io.jobplatform.assets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Private byte-store boundary; the local implementation is used only for development and Compose. */
public interface FileAssetStore {
    StoredFile store(UUID projectId, UUID assetId, String extension, InputStream content, long maximumBytes) throws IOException;

    Optional<StoredFile> find(UUID projectId, String storageKey) throws IOException;

    void delete(UUID projectId, String storageKey) throws IOException;

    record StoredFile(Path path, String storageKey, long sizeBytes, String sha256) { }
}
