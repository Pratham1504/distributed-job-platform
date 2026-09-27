package io.jobplatform.assets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobplatform.jobs.JobNotFoundException;
import io.jobplatform.projects.ProjectService;
import io.jobplatform.security.ProductPrincipal;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the durable metadata and private bytes for every product file asset. */
@Service
public class FileAssetService {
    private static final Logger log = LoggerFactory.getLogger(FileAssetService.class);
    private static final Set<String> CSV_CONTENT_TYPES = Set.of("text/csv", "application/csv", "application/vnd.ms-excel");
    private static final long DEFAULT_UPLOAD_MAXIMUM_BYTES = 10L * 1024 * 1024;
    private static final long DEFAULT_OUTPUT_MAXIMUM_BYTES = 50L * 1024 * 1024;
    private static final int MAXIMUM_ROWS = 100_000;
    private static final int HEADER_PREVIEW_SIZE = 12;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final FileAssetStore storage;
    private final long uploadMaximumBytes;
    private final long outputMaximumBytes;
    private final Duration retention;

    public FileAssetService(JdbcTemplate jdbc, ObjectMapper json, ProjectService projects, FileAssetStore storage,
                            @Value("${app.file-assets.upload-maximum-bytes:" + DEFAULT_UPLOAD_MAXIMUM_BYTES + "}") long uploadMaximumBytes,
                            @Value("${app.file-assets.output-maximum-bytes:" + DEFAULT_OUTPUT_MAXIMUM_BYTES + "}") long outputMaximumBytes,
                            @Value("${app.retention.artifacts:PT720H}") Duration retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.projects = projects;
        this.storage = storage;
        this.uploadMaximumBytes = uploadMaximumBytes;
        this.outputMaximumBytes = outputMaximumBytes;
        this.retention = retention;
        if (uploadMaximumBytes < 1 || outputMaximumBytes < 1 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("File asset limits and retention must be positive.");
        }
    }

    /** Stores a CSV only after validating its user-visible metadata and safe UTF-8/header preview. */
    public FileAssetResponse uploadSource(UUID projectId, ProductPrincipal principal, MultipartFile file) throws IOException {
        requireDashboardUser(projectId, principal);
        if (file == null || file.isEmpty()) throw new FileAssetValidationException("A non-empty CSV file is required.");
        if (file.getSize() > uploadMaximumBytes) {
            throw new FileAssetTooLargeException("File exceeds the " + uploadMaximumBytes + " byte limit.");
        }
        String contentType = normalisedContentType(file.getContentType());
        if (!CSV_CONTENT_TYPES.contains(contentType)) {
            throw new FileAssetValidationException("Only CSV uploads with a CSV content type are accepted.");
        }
        String filename = safeCsvFilename(file.getOriginalFilename());
        UUID assetId = UUID.randomUUID();
        FileAssetStore.StoredFile stored = storage.store(projectId, assetId, ".csv", file.getInputStream(), uploadMaximumBytes);
        try {
            CsvInspection inspection = inspectCsv(stored.path());
            FileAssetMetadata asset = new FileAssetMetadata(assetId, projectId, FileAssetKind.SOURCE_CSV, stored.storageKey(),
                    filename, "text/csv", stored.sizeBytes(), stored.sha256(), inspection.physicalDataRows(), inspection.header(),
                    principal.userId(), Instant.now(), Instant.now().plus(retention), null);
            insert(asset);
            audit(projectId, principal.userId(), "FILE_SOURCE_UPLOADED", assetId,
                    "{\"sizeBytes\":" + stored.sizeBytes() + ",\"sha256\":\"" + stored.sha256() + "\"}");
            log.info("event=file_source_uploaded projectId={} assetId={} sizeBytes={} rowCount={}", projectId, assetId,
                    stored.sizeBytes(), inspection.physicalDataRows());
            return FileAssetResponse.from(asset);
        } catch (RuntimeException | IOException exception) {
            storage.delete(projectId, stored.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public FileAssetResponse get(UUID projectId, ProductPrincipal principal, UUID assetId) {
        projects.requireAccess(projectId, principal);
        return FileAssetResponse.from(requireAvailable(projectId, assetId));
    }

    /** Returns project-owned source files so one upload can power many separate jobs. */
    @Transactional(readOnly = true)
    public List<FileAssetResponse> list(UUID projectId, ProductPrincipal principal, FileAssetKind kind) {
        projects.requireAccess(projectId, principal);
        if (kind != FileAssetKind.SOURCE_CSV) {
            throw new FileAssetValidationException("Only reusable source CSV assets can be listed.");
        }
        return jdbc.query("""
                select id,project_id,kind,storage_key,original_filename,content_type,size_bytes,sha256,row_count,header_json,
                       created_by,created_at,expires_at,deleted_at
                from file_assets
                where project_id=? and kind=? and deleted_at is null and (expires_at is null or expires_at > current_timestamp)
                order by created_at desc
                limit 100
                """, (rs, row) -> FileAssetResponse.from(map(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("kind"), rs.getString("storage_key"), rs.getString("original_filename"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), (Integer) rs.getObject("row_count"), rs.getString("header_json"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("deleted_at")))), projectId, kind.name());
    }

    @Transactional(readOnly = true)
    public DownloadableFile download(UUID projectId, ProductPrincipal principal, UUID assetId) throws IOException {
        projects.requireAccess(projectId, principal);
        FileAssetMetadata asset = requireAvailable(projectId, assetId);
        Path path = storage.find(projectId, asset.storageKey())
                .orElseThrow(() -> new JobNotFoundException("File asset not found."))
                .path();
        return new DownloadableFile(asset, path);
    }

    /**
     * Returns only a small in-memory CSV sample. Full output remains a streamed authorised download,
     * so a 100,000-row file never becomes an unbounded dashboard response.
     */
    @Transactional(readOnly = true)
    public CsvPreviewResponse preview(UUID projectId, ProductPrincipal principal, UUID assetId, int limit,
                                      String reason, String excludeReason) throws IOException {
        projects.requireAccess(projectId, principal);
        if (limit < 1 || limit > 50) throw new FileAssetValidationException("CSV preview limit must be between 1 and 50 rows.");
        if (reason != null && excludeReason != null) {
            throw new FileAssetValidationException("Use either reason or excludeReason when previewing a CSV.");
        }
        if ((reason != null && (reason.isBlank() || reason.length() > 80))
                || (excludeReason != null && (excludeReason.isBlank() || excludeReason.length() > 80))) {
            throw new FileAssetValidationException("CSV preview reason filters must be between 1 and 80 characters.");
        }
        FileAssetMetadata asset = requireAvailable(projectId, assetId);
        if (asset.kind() != FileAssetKind.CLEANED_CSV && asset.kind() != FileAssetKind.ERROR_CSV) {
            throw new FileAssetValidationException("Only generated CSV outputs can be previewed.");
        }
        Path path = storage.find(projectId, asset.storageKey())
                .orElseThrow(() -> new JobNotFoundException("File asset not found."))
                .path();
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                     .setAllowMissingColumnNames(false).build().parse(reader)) {
            List<String> header = new ArrayList<>(parser.getHeaderMap().keySet());
            boolean filtersByReason = reason != null || excludeReason != null;
            if (filtersByReason && !parser.getHeaderMap().containsKey("reason")) {
                throw new FileAssetValidationException("Reason filters are available only for an error CSV.");
            }
            List<List<String>> rows = new ArrayList<>(limit);
            boolean hasMore = false;
            for (CSVRecord record : parser) {
                String rowReason = filtersByReason ? record.get("reason") : null;
                if (reason != null && !reason.equals(rowReason)) continue;
                if (excludeReason != null && excludeReason.equals(rowReason)) continue;
                if (rows.size() == limit) {
                    hasMore = true;
                    break;
                }
                rows.add(record.toList());
            }
            return new CsvPreviewResponse(header, List.copyOf(rows), hasMore);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new FileAssetValidationException("CSV output is not valid UTF-8.");
        } catch (FileAssetValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new FileAssetValidationException("CSV output cannot be previewed.");
        }
    }

    /** Verifies a process-job input before the job/outbox transaction is committed. */
    @Transactional(readOnly = true)
    public void requireProcessableSource(UUID projectId, UUID assetId) {
        FileAssetMetadata asset = requireAvailable(projectId, assetId);
        if (asset.kind() != FileAssetKind.SOURCE_CSV) {
            throw new JobNotFoundException("Source file asset not found.");
        }
    }

    /** Worker-only resolver. Its project lookup keeps a stored payload from crossing project boundaries. */
    @Transactional(readOnly = true)
    public WorkerSource resolveSource(UUID projectId, UUID assetId) throws IOException {
        FileAssetMetadata asset = requireAvailable(projectId, assetId);
        if (asset.kind() != FileAssetKind.SOURCE_CSV) {
            throw new IllegalArgumentException("The requested file asset is not a source CSV.");
        }
        Path path = storage.find(projectId, asset.storageKey())
                .orElseThrow(() -> new IllegalArgumentException("Source file asset is unavailable."))
                .path();
        return new WorkerSource(asset, path);
    }

    /**
     * Stores an effect-stable output asset. A retry uses the same derived ID, avoiding logical
     * duplicate output assets even if the first worker dies after writing private bytes.
     */
    @Transactional
    public FileAssetMetadata createGeneratedCsv(UUID projectId, UUID ownerId, UUID effectId, FileAssetKind kind,
                                                 String filename, byte[] content, int rowCount) throws IOException {
        if (kind != FileAssetKind.CLEANED_CSV && kind != FileAssetKind.ERROR_CSV) {
            throw new IllegalArgumentException("Only cleaned and error CSV outputs may be generated by a file job.");
        }
        UUID assetId = UUID.nameUUIDFromBytes((effectId + ":" + kind.name()).getBytes(StandardCharsets.UTF_8));
        FileAssetMetadata existing = findAvailable(projectId, assetId);
        if (existing != null) return existing;
        FileAssetStore.StoredFile stored = storage.store(projectId, assetId, ".csv", new java.io.ByteArrayInputStream(content), outputMaximumBytes);
        FileAssetMetadata asset = new FileAssetMetadata(assetId, projectId, kind, stored.storageKey(), safeGeneratedFilename(filename),
                "text/csv", stored.sizeBytes(), stored.sha256(), rowCount, null, ownerId, Instant.now(), Instant.now().plus(retention), null);
        try {
            insert(asset);
            log.info("event=file_output_created projectId={} assetId={} kind={} rows={}", projectId, assetId, kind, rowCount);
            return asset;
        } catch (DataIntegrityViolationException duplicate) {
            FileAssetMetadata raced = findAvailable(projectId, assetId);
            if (raced != null) return raced;
            throw duplicate;
        }
    }

    /** Removes expired private bytes and leaves metadata as a deleted audit/history record. */
    @Transactional
    public int purgeExpired() {
        Instant now = Instant.now();
        List<FileAssetMetadata> expired = jdbc.query("""
                select id,project_id,kind,storage_key,original_filename,content_type,size_bytes,sha256,row_count,header_json,
                       created_by,created_at,expires_at,deleted_at
                from file_assets where deleted_at is null and expires_at < ?
                for update skip locked
                """, (rs, row) -> map(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("kind"), rs.getString("storage_key"), rs.getString("original_filename"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), (Integer) rs.getObject("row_count"), rs.getString("header_json"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("deleted_at"))), Timestamp.from(now));
        int removed = 0;
        for (FileAssetMetadata asset : expired) {
            try {
                storage.delete(asset.projectId(), asset.storageKey());
                jdbc.update("update file_assets set deleted_at=? where id=? and deleted_at is null", Timestamp.from(now), asset.id());
                removed++;
            } catch (IOException exception) {
                // Leave metadata live so a later cleanup run can retry byte deletion safely.
            }
        }
        return removed;
    }

    private FileAssetMetadata requireAvailable(UUID projectId, UUID assetId) {
        FileAssetMetadata asset = findAvailable(projectId, assetId);
        if (asset == null) throw new JobNotFoundException("File asset not found.");
        return asset;
    }

    private FileAssetMetadata findAvailable(UUID projectId, UUID assetId) {
        return jdbc.query("""
                select id,project_id,kind,storage_key,original_filename,content_type,size_bytes,sha256,row_count,header_json,
                       created_by,created_at,expires_at,deleted_at
                from file_assets
                where id=? and project_id=? and deleted_at is null and (expires_at is null or expires_at > current_timestamp)
                """, rs -> rs.next() ? map(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("kind"), rs.getString("storage_key"), rs.getString("original_filename"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), (Integer) rs.getObject("row_count"), rs.getString("header_json"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("deleted_at"))) : null, assetId, projectId);
    }

    private void insert(FileAssetMetadata asset) {
        jdbc.update("""
                insert into file_assets (id,project_id,kind,storage_key,original_filename,content_type,size_bytes,sha256,row_count,
                                         header_json,created_by,created_at,expires_at)
                values (?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?)
                """, asset.id(), asset.projectId(), asset.kind().name(), asset.storageKey(), asset.filename(), asset.contentType(),
                asset.sizeBytes(), asset.sha256(), asset.rowCount(), headerJson(asset.header()), asset.createdBy(),
                Timestamp.from(asset.createdAt()), Timestamp.from(asset.expiresAt()));
    }

    private void audit(UUID projectId, UUID actorId, String eventType, UUID assetId, String metadata) {
        jdbc.update("""
                insert into audit_events (id,project_id,actor_type,actor_id,event_type,resource_type,resource_id,metadata,created_at)
                values (?,?,'USER',?,?,'FILE_ASSET',?,cast(? as jsonb),?)
                """, UUID.randomUUID(), projectId, actorId, eventType, assetId, metadata, Timestamp.from(Instant.now()));
    }

    private CsvInspection inspectCsv(Path path) throws IOException {
        int newlineCount = 0;
        boolean hasAnyCharacter = false;
        boolean endsWithNewline = false;
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                hasAnyCharacter = true;
                for (int index = 0; index < read; index++) {
                    char value = buffer[index];
                    if (value == '\u0000') throw new FileAssetValidationException("Binary files are not accepted as CSV uploads.");
                    if (value == '\n') newlineCount++;
                    endsWithNewline = value == '\n';
                }
            }
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new FileAssetValidationException("CSV uploads must be valid UTF-8 text.");
        }
        if (!hasAnyCharacter) throw new FileAssetValidationException("File must not be empty.");
        int physicalDataRows = Math.max(0, newlineCount + (endsWithNewline ? 0 : 1) - 1);
        if (physicalDataRows > MAXIMUM_ROWS) {
            throw new FileAssetValidationException("CSV uploads are limited to " + MAXIMUM_ROWS + " rows.");
        }
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setAllowMissingColumnNames(false).build().parse(reader)) {
            List<String> header = new ArrayList<>(parser.getHeaderMap().keySet());
            if (header.isEmpty() || header.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new FileAssetValidationException("CSV uploads must include a non-empty header row.");
            }
            return new CsvInspection(physicalDataRows, header.stream().limit(HEADER_PREVIEW_SIZE).toList());
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new FileAssetValidationException("CSV uploads must be valid UTF-8 text.");
        } catch (IllegalArgumentException exception) {
            throw new FileAssetValidationException("CSV header is malformed or contains duplicate column names.");
        }
    }

    private FileAssetMetadata map(UUID id, UUID projectId, String kind, String storageKey, String filename, String contentType,
                                  long sizeBytes, String sha256, Integer rowCount, String headerJson, UUID createdBy,
                                  Instant createdAt, Instant expiresAt, Instant deletedAt) {
        return new FileAssetMetadata(id, projectId, FileAssetKind.valueOf(kind), storageKey, filename, contentType, sizeBytes,
                sha256, rowCount, readHeader(headerJson), createdBy, createdAt, expiresAt, deletedAt);
    }

    private List<String> readHeader(String source) {
        if (source == null) return null;
        try {
            return json.readValue(source, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored file asset header is not valid JSON.", exception);
        }
    }

    private String headerJson(List<String> header) {
        if (header == null) return null;
        try {
            return json.writeValueAsString(header);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialise file asset header.", exception);
        }
    }

    private String normalisedContentType(String contentType) {
        if (contentType == null) return "";
        int parameter = contentType.indexOf(';');
        return (parameter >= 0 ? contentType.substring(0, parameter) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    private String safeCsvFilename(String value) {
        if (value == null) throw new FileAssetValidationException("CSV filename is required.");
        String filename = value.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) filename = filename.substring(slash + 1);
        filename = filename.trim().replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank() || filename.length() > 255 || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new FileAssetValidationException("Only .csv filenames up to 255 characters are accepted.");
        }
        return filename;
    }

    private String safeGeneratedFilename(String value) {
        String filename = value == null ? "processed.csv" : value.replaceAll("[\\p{Cntrl}/\\\\]", "").trim();
        if (filename.isBlank()) filename = "processed.csv";
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) filename += ".csv";
        return filename.length() <= 255 ? filename : filename.substring(0, 251) + ".csv";
    }

    private void requireDashboardUser(UUID projectId, ProductPrincipal principal) {
        if (principal == null || principal.isApiKey()) throw new JobNotFoundException("Project not found.");
        projects.requireOwnership(projectId, principal.userId());
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record DownloadableFile(FileAssetMetadata asset, Path path) { }
    public record WorkerSource(FileAssetMetadata asset, Path path) { }
    private record CsvInspection(int physicalDataRows, List<String> header) { }
}
