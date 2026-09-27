package io.jobplatform.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JobRequestValidator {
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    public void validate(String idempotencyKey, CreateJobRequest request) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new JobValidationException("Idempotency-Key must be between 8 and 128 characters.");
        }
        if (request.payload() == null || !request.payload().isObject()) {
            throw new JobValidationException("payload must be a JSON object.");
        }
        if (request.payload().toString().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new JobValidationException("payload must not exceed 64 KiB.");
        }
        validateSchedule(request.scheduledAt());
        validateWebhook(request.webhookUrl());
        switch (request.jobType()) {
            case GENERATE_REPORT -> validateReport(request.payload());
            case SEND_EMAIL -> validateEmail(request.payload());
            case PROCESS_FILE -> validateFile(request.payload());
            case SEND_NOTIFICATION -> validateNotification(request.payload());
        }
    }

    private void validateSchedule(Instant scheduledAt) {
        if (scheduledAt == null) return;
        Instant now = Instant.now();
        if (scheduledAt.isBefore(now.plusSeconds(10)) || scheduledAt.isAfter(now.plus(Duration.ofDays(30)))) {
            throw new JobValidationException("scheduledAt must be between 10 seconds and 30 days in the future.");
        }
    }

    private void validateWebhook(String webhookUrl) {
        if (webhookUrl == null) return;
        if (webhookUrl.length() > 2048) {
            throw new JobValidationException("webhookUrl must not exceed 2048 characters.");
        }
        try {
            URI uri = new URI(webhookUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new JobValidationException("webhookUrl must be an absolute HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw new JobValidationException("webhookUrl must be a valid URI.");
        }
    }

    private void validateReport(JsonNode payload) {
        exactFields(payload, Set.of("template", "periodStart", "periodEnd"));
        oneOf(text(payload, "template"), Set.of("SALES_SUMMARY", "JOB_AUDIT"), "template");
        date(text(payload, "periodStart"), "periodStart");
        date(text(payload, "periodEnd"), "periodEnd");
    }

    private void validateEmail(JsonNode payload) {
        exactFields(payload, Set.of("template", "recipient", "variables"));
        oneOf(text(payload, "template"), Set.of("ORDER_CONFIRMED", "JOB_FAILED"), "template");
        String recipient = text(payload, "recipient");
        if (!recipient.contains("@") || recipient.length() > 320) {
            throw new JobValidationException("recipient must be a valid email address.");
        }
        JsonNode variables = payload.get("variables");
        if (variables == null || !variables.isObject() || variables.size() > 20) {
            throw new JobValidationException("variables must be an object with at most 20 entries.");
        }
        variables.elements().forEachRemaining(value -> {
            if (!value.isTextual()) throw new JobValidationException("variables values must be strings.");
        });
    }

    private void validateFile(JsonNode payload) {
        exactFields(payload, Set.of("sourceAssetId", "operation"));
        String sourceAssetId = text(payload, "sourceAssetId");
        try {
            java.util.UUID.fromString(sourceAssetId);
        } catch (IllegalArgumentException exception) {
            throw new JobValidationException("sourceAssetId must be a UUID.");
        }
        oneOf(text(payload, "operation"), Set.of("CSV_VALIDATE", "CSV_NORMALIZE"), "operation");
    }

    private void validateNotification(JsonNode payload) {
        exactFields(payload, Set.of("channel", "recipient", "template"));
        oneOf(text(payload, "channel"), Set.of("EMAIL"), "channel");
        oneOf(text(payload, "template"), Set.of("JOB_COMPLETED", "JOB_FAILED"), "template");
        String recipient = text(payload, "recipient");
        if (!recipient.contains("@") || recipient.length() > 320) {
            throw new JobValidationException("recipient must be a valid email address.");
        }
    }

    private void exactFields(JsonNode payload, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        payload.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new JobValidationException("payload fields do not match the selected job type.");
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new JobValidationException(field + " must be a non-blank string.");
        }
        return value.asText();
    }

    private void oneOf(String value, Set<String> accepted, String field) {
        if (!accepted.contains(value)) throw new JobValidationException(field + " has an unsupported value.");
    }

    private void date(String value, String field) {
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new JobValidationException(field + " must be an ISO-8601 date.");
        }
    }
}
