package io.jobplatform.jobs;

import java.util.List;

public record JobPageResponse(List<JobDetailsResponse> items, String nextCursor) { }
