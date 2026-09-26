package io.jobplatform.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Size(min = 3, max = 100) String projectName,
                              @NotBlank @jakarta.validation.constraints.Email String email,
                              @NotBlank @Size(min = 12, max = 128) String password) { }
