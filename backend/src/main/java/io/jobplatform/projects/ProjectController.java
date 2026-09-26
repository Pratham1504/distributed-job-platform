package io.jobplatform.projects;

import io.jobplatform.security.ProductPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects=projects; }
    @GetMapping public List<ProjectResponse> list(@AuthenticationPrincipal ProductPrincipal principal) { return projects.list(principal.userId()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal ProductPrincipal principal, @Valid @RequestBody CreateProjectRequest request) { return projects.create(principal.userId(),request); }
}
