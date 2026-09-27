package io.jobplatform.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the published OpenAPI contract aligned with every externally reachable controller route.
 * This deliberately reads the design-pack contract, which is the project source of truth.
 */
class OpenApiContractDocumentTest {
    private static final Set<String> DOCUMENTED_OPERATIONS = Set.of(
            "POST /auth/register", "POST /auth/login", "POST /auth/refresh",
            "GET /projects", "POST /projects",
            "GET /projects/{projectId}/api-keys", "POST /projects/{projectId}/api-keys",
            "POST /api-keys/{keyId}/revoke",
            "GET /projects/{projectId}/files", "POST /projects/{projectId}/files",
            "GET /projects/{projectId}/files/{assetId}", "GET /projects/{projectId}/files/{assetId}/preview", "GET /projects/{projectId}/files/{assetId}/download",
            "GET /projects/{projectId}/artifacts/{artifactId}",
            "GET /projects/{projectId}/jobs", "POST /projects/{projectId}/jobs",
            "GET /projects/{projectId}/jobs/{jobId}", "POST /projects/{projectId}/jobs/{jobId}/cancel",
            "POST /projects/{projectId}/jobs/{jobId}/retry", "GET /operator/workers");

    @Test
    void contractDocumentsEveryPublicControllerOperationAndCriticalWireShapes() throws Exception {
        Path contract = Path.of("..", "job-platform-design-pack", "job-platform-design 2", "api", "openapi.yaml")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(contract), "The OpenAPI contract must remain in the design pack.");

        Map<String, Object> root = new Yaml().load(Files.readString(contract));
        assertEquals("3.0.3", root.get("openapi"));
        Map<String, Object> paths = map(root.get("paths"));
        Set<String> documented = paths.entrySet().stream()
                .flatMap(path -> map(path.getValue()).keySet().stream()
                        .filter(method -> Set.of("get", "post", "put", "patch", "delete").contains(method))
                        .map(method -> method.toUpperCase() + " " + path.getKey()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(DOCUMENTED_OPERATIONS, documented,
                "Add or remove a contract operation together with its controller endpoint.");

        Map<String, Object> login = map(map(paths.get("/auth/login")).get("post"));
        assertTrue(map(login.get("responses")).containsKey("429"), "Login rate limiting must be documented.");

        Map<String, Object> download = map(map(paths.get("/projects/{projectId}/files/{assetId}/download")).get("get"));
        Map<String, Object> downloadResponse = map(map(download.get("responses")).get("200"));
        assertTrue(map(downloadResponse.get("content")).containsKey("text/csv"),
                "The application streams CSV bytes; it does not return a redirect object.");

        Map<String, Object> artifact = map(map(paths.get("/projects/{projectId}/artifacts/{artifactId}")).get("get"));
        assertFalse(map(artifact.get("responses")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, "OpenAPI node must be a mapping.");
        return (Map<String, Object>) value;
    }
}
