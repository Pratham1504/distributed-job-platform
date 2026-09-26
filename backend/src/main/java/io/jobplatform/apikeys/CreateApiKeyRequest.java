package io.jobplatform.apikeys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(@NotBlank @Size(min = 3, max = 100) String name) { }
