package io.jobplatform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(@Email @NotBlank String email, @NotBlank @Size(min = 12, max = 128) String password) { }
