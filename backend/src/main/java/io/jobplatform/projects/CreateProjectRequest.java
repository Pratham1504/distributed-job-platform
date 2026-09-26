package io.jobplatform.projects;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateProjectRequest(@NotBlank @Size(min = 3, max = 100) String name) { }
