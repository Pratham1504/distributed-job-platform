package io.jobplatform.assets;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe file metadata returned to an owning dashboard user. */
public record FileAssetResponse(
        UUID id,
        FileAssetKind kind,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        Integer rowCount,
        List<String> header,
        Instant createdAt) {

    static FileAssetResponse from(FileAssetMetadata asset) {
        return new FileAssetResponse(asset.id(), asset.kind(), asset.filename(), asset.contentType(), asset.sizeBytes(),
                asset.sha256(), asset.rowCount(), asset.header(), asset.createdAt());
    }
}
