package com.zachary.transportation_reliability_platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the deliberately minimal public WebSocket message schema. */
class LiveUpdateMessageUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesOnlyThePublicInvalidationFields() {
        JsonNode message = objectMapper.valueToTree(
                new LiveUpdateMessage(
                        "vehicle-positions",
                        Instant.parse("2026-09-19T12:00:00Z")
                )
        );
        List<String> fieldNames = new ArrayList<>();
        message.fieldNames().forEachRemaining(fieldNames::add);

        assertThat(fieldNames).containsExactlyInAnyOrder("type", "emittedAt");
        assertThat(message.path("type").asText()).isEqualTo("vehicle-positions");
    }
}
