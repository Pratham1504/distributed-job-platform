package io.jobplatform.jobs;

import jakarta.validation.Valid;
import java.util.UUID;
import io.jobplatform.security.ProductPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/jobs")
public class JobController {
    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobAcceptedResponse submit(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProductPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateJobRequest request) {
        return jobService.submit(projectId, principal.userId(), idempotencyKey, request);
    }

    @GetMapping("/{jobId}")
    public JobSummaryResponse get(@PathVariable UUID projectId, @PathVariable UUID jobId, @AuthenticationPrincipal ProductPrincipal principal) {
        return jobService.get(projectId, principal.userId(), jobId);
    }
}
