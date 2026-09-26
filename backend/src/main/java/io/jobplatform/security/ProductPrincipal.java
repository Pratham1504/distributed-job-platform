package io.jobplatform.security;

import java.util.UUID;

/**
 * The authenticated subject for both browser/API users and project-scoped API keys.
 * API-key principals deliberately have no user identity, so they cannot be used on
 * account, project, key-management, or operator routes.
 */
public record ProductPrincipal(UUID userId, String email, String role, UUID apiKeyProjectId, UUID apiClientId) {
    public ProductPrincipal(UUID userId, String email, String role) {
        this(userId, email, role, null, null);
    }

    public static ProductPrincipal apiKey(UUID projectId, UUID apiClientId) {
        return new ProductPrincipal(null, null, "API_KEY", projectId, apiClientId);
    }

    public boolean isApiKey() {
        return apiKeyProjectId != null;
    }
}
