package io.jobplatform.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A private local-filesystem adapter. It uses generated keys only and keeps every project in a
 * separate directory, so an asset ID is never interpreted as a user-controlled filesystem path.
 */
@Service
public class LocalFileAssetStore implements FileAssetStore {
    private final Path root;

    public LocalFileAssetStore(@Value("${app.file-assets.local-root:./var/file-assets}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(UUID projectId, UUID assetId, String extension, InputStream content, long maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("Asset maximum size must be positive.");
        if (!".csv".equals(extension)) throw new IllegalArgumentException("Unsupported private asset extension.");
        Path projectDirectory = projectDirectory(projectId);
        Files.createDirectories(projectDirectory);
        String storageKey = projectId + "/" + assetId + extension;
        Path target = pathFor(projectId, storageKey);
        Path temporary = Files.createTempFile(projectDirectory, assetId + "-", ".tmp");
        MessageDigest digest = sha256();
        long written = 0;
        try {
            try (InputStream input = content;
                 OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > maximumBytes) {
                        throw new FileAssetTooLargeException("File exceeds the " + maximumBytes + " byte limit.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (written < 1) throw new FileAssetValidationException("File must not be empty.");
            move(temporary, target);
            return new StoredFile(target, storageKey, written, HexFormat.of().formatHex(digest.digest()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<StoredFile> find(UUID projectId, String storageKey) throws IOException {
        Path path = pathFor(projectId, storageKey);
        if (!Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(new StoredFile(path, storageKey, Files.size(path), null));
    }

    @Override
    public void delete(UUID projectId, String storageKey) throws IOException {
        Files.deleteIfExists(pathFor(projectId, storageKey));
    }

    private Path pathFor(UUID projectId, String storageKey) {
        String prefix = projectId + "/";
        if (storageKey == null || !storageKey.startsWith(prefix) || !storageKey.endsWith(".csv")) {
            throw new IllegalArgumentException("Invalid asset storage key.");
        }
        Path path = root.resolve(storageKey).normalize();
        Path projectDirectory = projectDirectory(projectId);
        if (!path.startsWith(projectDirectory)) throw new IllegalArgumentException("Invalid asset storage path.");
        return path;
    }

    private Path projectDirectory(UUID projectId) {
        Path directory = root.resolve(projectId.toString()).normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid project asset location.");
        return directory;
    }

    private void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
