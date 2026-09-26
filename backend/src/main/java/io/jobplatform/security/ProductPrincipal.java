package io.jobplatform.security;

import java.util.UUID;

public record ProductPrincipal(UUID userId, String email, String role) { }
