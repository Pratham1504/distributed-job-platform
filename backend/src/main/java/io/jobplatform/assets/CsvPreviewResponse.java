package io.jobplatform.assets;

import java.util.List;

/** A deliberately bounded, project-authorised sample of a CSV asset for the result UI. */
public record CsvPreviewResponse(List<String> header, List<List<String>> rows, boolean hasMore) { }
