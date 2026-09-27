package io.jobplatform.artifacts;

import io.jobplatform.jobs.JobNotFoundException;
import io.jobplatform.projects.ProjectService;
import io.jobplatform.security.ProductPrincipal;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves only a project's own generated artifacts after an ownership/API-key check. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts")
public class ArtifactController {
    private final ArtifactStore artifacts;
    private final ProjectService projects;

    public ArtifactController(ArtifactStore artifacts, ProjectService projects) {
        this.artifacts = artifacts;
        this.projects = projects;
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @AuthenticationPrincipal ProductPrincipal principal) throws IOException {
        projects.requireAccess(projectId, principal);
        ArtifactStore.StoredArtifact artifact = artifacts.findJson(projectId, artifactId)
                .orElseThrow(() -> new JobNotFoundException("Artifact not found."));
        String filename = artifactId + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(artifact.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new FileSystemResource(artifact.path()));
    }
}
