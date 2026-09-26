package io.jobplatform.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobRequestValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JobRequestValidator validator = new JobRequestValidator();

    @Test
    void acceptsAValidReportRequest() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                JobType.GENERATE_REPORT,
                objectMapper.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                JobPriority.DEFAULT, null, 4, null);

        assertDoesNotThrow(() -> validator.validate("report-001", request));
    }

    @Test
    void rejectsPayloadFromAnotherJobType() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                JobType.SEND_EMAIL,
                objectMapper.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                JobPriority.DEFAULT, null, 4, null);

        assertThrows(JobValidationException.class, () -> validator.validate("email-001", request));
    }

    @Test
    void rejectsUnknownPayloadFields() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                JobType.GENERATE_REPORT,
                objectMapper.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\",\"extra\":\"no\"}"),
                JobPriority.DEFAULT, null, 4, null);

        assertThrows(JobValidationException.class, () -> validator.validate("report-002", request));
    }

    @Test
    void rejectsAJobScheduledTooSoon() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                JobType.GENERATE_REPORT,
                objectMapper.readTree("{\"template\":\"SALES_SUMMARY\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}"),
                JobPriority.DEFAULT, Instant.now().plusSeconds(5), 4, null);

        assertThrows(JobValidationException.class, () -> validator.validate("report-003", request));
    }
}
