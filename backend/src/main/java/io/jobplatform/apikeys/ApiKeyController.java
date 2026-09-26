package io.jobplatform.apikeys;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import io.jobplatform.security.ProductPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {
    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) { this.keys = keys; }

    @GetMapping("/projects/{projectId}/api-keys")
    public List<ApiKeyMetadata> list(@PathVariable UUID projectId, @AuthenticationPrincipal ProductPrincipal principal) {
        return keys.list(projectId, principal.userId());
    }

    @PostMapping("/projects/{projectId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKey create(@PathVariable UUID projectId, @AuthenticationPrincipal ProductPrincipal principal,
                                 @Valid @RequestBody CreateApiKeyRequest request) {
        return keys.create(projectId, principal.userId(), request);
    }

    @PostMapping("/api-keys/{keyId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID keyId, @AuthenticationPrincipal ProductPrincipal principal) {
        keys.revoke(keyId, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
