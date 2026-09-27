package io.jobplatform.assets;

import io.jobplatform.security.ProductPrincipal;
import java.io.IOException;
import java.util.List;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Project-scoped source upload, metadata, and download endpoints. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
public class FileAssetController {
    private final FileAssetService assets;

    public FileAssetController(FileAssetService assets) {
        this.assets = assets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileAssetResponse upload(@PathVariable UUID projectId, @AuthenticationPrincipal ProductPrincipal principal,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        return assets.uploadSource(projectId, principal, file);
    }

    /** Lists reusable, live source assets for the selected project. */
    @GetMapping
    public List<FileAssetResponse> list(@PathVariable UUID projectId, @AuthenticationPrincipal ProductPrincipal principal,
                                        @RequestParam(defaultValue = "SOURCE_CSV") FileAssetKind kind) {
        return assets.list(projectId, principal, kind);
    }

    @GetMapping("/{assetId}")
    public FileAssetResponse get(@PathVariable UUID projectId, @PathVariable UUID assetId,
                                 @AuthenticationPrincipal ProductPrincipal principal) {
        return assets.get(projectId, principal, assetId);
    }

    @GetMapping("/{assetId}/preview")
    public CsvPreviewResponse preview(@PathVariable UUID projectId, @PathVariable UUID assetId,
                                      @AuthenticationPrincipal ProductPrincipal principal,
                                      @RequestParam(defaultValue = "12") int limit,
                                      @RequestParam(required = false) String reason,
                                      @RequestParam(required = false) String excludeReason) throws IOException {
        return assets.preview(projectId, principal, assetId, limit, reason, excludeReason);
    }

    @GetMapping("/{assetId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID projectId, @PathVariable UUID assetId,
                                                        @AuthenticationPrincipal ProductPrincipal principal) throws IOException {
        FileAssetService.DownloadableFile file = assets.download(projectId, principal, assetId);
        MediaType contentType = MediaType.parseMediaType(file.asset().contentType());
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(Files.size(file.path()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.asset().filename()).build().toString())
                .body(new FileSystemResource(file.path()));
    }
}
