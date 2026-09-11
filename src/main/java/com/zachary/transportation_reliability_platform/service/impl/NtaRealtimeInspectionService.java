package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.response.NtaFeedSummaryResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaTripUpdatePreviewResponse;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NtaRealtimeInspectionService {

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final ObjectMapper objectMapper;

    /**
     * Downloads the live feed and counts the types of entity it contains.
     * This method does not save anything to PostgreSQL or publish Kafka events.
     */
    public NtaFeedSummaryResponse inspectFeed() {
        String rawFeed = ntaGtfsRealtimeClient.fetchRawFeed();

        try {
            JsonNode root = objectMapper.readTree(rawFeed);
            JsonNode entities = root.path("entity");

            int tripUpdateCount = 0;
            int vehiclePositionCount = 0;
            int alertCount = 0;

            for (JsonNode entity : entities) {
                if (hasAnyField(entity, "tripUpdate", "trip_update")) {
                    tripUpdateCount++;
                }

                if (hasAnyField(entity, "vehicle", "vehiclePosition", "vehicle_position")) {
                    vehiclePositionCount++;
                }

                if (hasAnyField(entity, "alert")) {
                    alertCount++;
                }
            }

            long timestampSeconds = root.path("header")
                    .path("timestamp")
                    .asLong(0);

            Instant feedTimestamp = timestampSeconds == 0
                    ? null
                    : Instant.ofEpochSecond(timestampSeconds);

            return new NtaFeedSummaryResponse(
                    feedTimestamp,
                    entities.size(),
                    tripUpdateCount,
                    vehiclePositionCount,
                    alertCount
            );

        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA API returned invalid JSON"
            );
        }
    }

    /**
     * Supports both camelCase and snake_case JSON field names.
     */
    private boolean hasAnyField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extracts a small number of real stop-level updates from the NTA feed.
     * No events are saved or sent to Kafka in this step.
     */
    public List<NtaTripUpdatePreviewResponse> previewTripUpdates(int limit) {
        String rawFeed = ntaGtfsRealtimeClient.fetchRawFeed();
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        try {
            JsonNode root = objectMapper.readTree(rawFeed);
            JsonNode entities = root.path("entity");

            List<NtaTripUpdatePreviewResponse> previews = new ArrayList<>();

            for (JsonNode entity : entities) {
                JsonNode tripUpdate = entity.get("trip_update");

                if (tripUpdate == null) {
                    continue;
                }

                JsonNode trip = tripUpdate.path("trip");

                String externalTripId = getText(trip, "trip_id");
                String externalRouteId = getText(trip, "route_id");
                JsonNode stopTimeUpdates = tripUpdate.path("stop_time_update");

                for (JsonNode stopTimeUpdate : stopTimeUpdates) {
                    previews.add(new NtaTripUpdatePreviewResponse(
                            externalTripId,
                            externalRouteId,
                            getText(stopTimeUpdate, "stop_id"),
                            getInteger(stopTimeUpdate, "stop_sequence"),
                            getNestedDelay(stopTimeUpdate, "arrival"),
                            getNestedDelay(stopTimeUpdate, "departure")
                    ));

                    if (previews.size() >= safeLimit) {
                        return previews;
                    }
                }
            }

            return previews;

        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA API returned invalid JSON"
            );
        }
    }

    /**
     * Reads an optional text field without converting missing values to empty text.
     */
    private String getText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull()
                ? null
                : value.asText();
    }

    /**
     * Reads an optional integer field.
     */
    private Integer getInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull()
                ? null
                : value.asInt();
    }

    /**
     * Reads arrival.delay or departure.delay from a GTFS-Realtime stop update.
     */
    private Integer getNestedDelay(JsonNode stopTimeUpdate, String timeType) {
        JsonNode delay = stopTimeUpdate.path(timeType).get("delay");

        return delay == null || delay.isNull()
                ? null
                : delay.asInt();
    }
}