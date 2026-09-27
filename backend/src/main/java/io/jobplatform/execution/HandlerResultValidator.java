package io.jobplatform.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Executable equivalent of contracts/handler-results.v1.json. Keeping this validation at the
 * finalisation boundary means malformed handler output cannot become durable API data.
 */
@Service
public class HandlerResultValidator {
    public void validate(ObjectNode result) {
        String type = text(result, "type");
        switch (type) {
            case "GENERATE_REPORT" -> {
                exactFields(result, Set.of("type", "artifactRef"));
                bounded(text(result, "artifactRef"), "artifactRef", 512);
            }
            case "SEND_EMAIL", "SEND_NOTIFICATION" -> {
                exactFields(result, Set.of("type", "providerMessageId"));
                bounded(text(result, "providerMessageId"), "providerMessageId", 256);
            }
            case "PROCESS_FILE" -> validateFileResult(result);
            default -> throw new IllegalArgumentException("Unsupported handler result type.");
        }
    }

    private void validateFileResult(ObjectNode result) {
        exactFields(result, Set.of("type", "sourceAssetId", "cleanedAssetId", "errorAssetId", "totalRows", "validRows",
                "rejectedRows", "duplicatesRemoved"));
        uuid(text(result, "sourceAssetId"), "sourceAssetId");
        uuid(text(result, "cleanedAssetId"), "cleanedAssetId");
        uuid(text(result, "errorAssetId"), "errorAssetId");
        nonNegativeInteger(result.get("totalRows"), "totalRows");
        nonNegativeInteger(result.get("validRows"), "validRows");
        nonNegativeInteger(result.get("rejectedRows"), "rejectedRows");
        nonNegativeInteger(result.get("duplicatesRemoved"), "duplicatesRemoved");
    }

    private void exactFields(ObjectNode result, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        result.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Handler result fields do not match its contract.");
    }

    private String text(ObjectNode result, String field) {
        JsonNode value = result.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Handler result " + field + " must be a non-blank string.");
        }
        return value.asText();
    }

    private void uuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Handler result " + field + " must be a UUID.");
        }
    }

    private void bounded(String value, String field, int maximum) {
        if (value.length() > maximum) throw new IllegalArgumentException("Handler result " + field + " is too long.");
    }

    private void nonNegativeInteger(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || value.canConvertToInt() == false || value.asInt() < 0) {
            throw new IllegalArgumentException("Handler result " + field + " must be a non-negative integer.");
        }
    }
}
