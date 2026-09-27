package io.jobplatform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandlerResultValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HandlerResultValidator validator = new HandlerResultValidator();

    @Test
    void acceptsThePublishedProcessFileResultShape() {
        ObjectNode result = json.createObjectNode();
        result.put("type", "PROCESS_FILE");
        result.put("sourceAssetId", UUID.randomUUID().toString());
        result.put("cleanedAssetId", UUID.randomUUID().toString());
        result.put("errorAssetId", UUID.randomUUID().toString());
        result.put("totalRows", 10);
        result.put("validRows", 8);
        result.put("rejectedRows", 1);
        result.put("duplicatesRemoved", 1);
        assertDoesNotThrow(() -> validator.validate(result));
    }

    @Test
    void rejectsAProcessFileResultThatLeaksAnUndocumentedField() {
        ObjectNode result = json.createObjectNode();
        result.put("type", "PROCESS_FILE");
        result.put("sourceAssetId", UUID.randomUUID().toString());
        result.put("cleanedAssetId", UUID.randomUUID().toString());
        result.put("errorAssetId", UUID.randomUUID().toString());
        result.put("totalRows", 1);
        result.put("validRows", 1);
        result.put("rejectedRows", 0);
        result.put("duplicatesRemoved", 0);
        result.put("effectId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(result));
    }
}
