package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.response.NtaVehiclePositionIngestionResponse;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converts NTA's Vehicles JSON response into normalized Kafka events.
 *
 * <p>A vehicle location is useful even when NTA cannot attach it to a static
 * trip, so this service does not discard otherwise-valid GPS coordinates when
 * {@code trip_id} or {@code route_id} is absent.</p>
 */
@Service
@RequiredArgsConstructor
public class NtaVehiclePositionIngestionService {

    private static final int DEFAULT_MAX_EVENTS = 0;
    private static final int MAX_MANUAL_EVENTS = 1_000;

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final VehiclePositionEventProducer vehiclePositionEventProducer;
    private final ObjectMapper objectMapper;

    /**
     * Downloads one NTA snapshot and publishes all valid vehicle positions.
     * A limit of zero deliberately means no limit.
     */
    public NtaVehiclePositionIngestionResponse publishVehiclePositions(
            int maxEvents
    ) {
        validateLimit(maxEvents);

        String rawFeed = ntaGtfsRealtimeClient.fetchRawVehicleFeed();

        try {
            JsonNode root = objectMapper.readTree(rawFeed);
            Instant feedTimestamp = feedTimestamp(root);
            List<VehiclePositionEvent> events = new ArrayList<>();
            int scannedVehicleCount = 0;
            int skippedVehicleCount = 0;

            for (JsonNode entity : root.path("entity")) {
                JsonNode vehicle = entity.get("vehicle");

                if (vehicle == null || vehicle.isNull()) {
                    continue;
                }

                scannedVehicleCount++;
                VehiclePositionEvent event = toEvent(vehicle, feedTimestamp);

                if (event == null) {
                    skippedVehicleCount++;
                    continue;
                }

                if (maxEvents > 0 && events.size() >= maxEvents) {
                    break;
                }

                events.add(event);
            }

            vehiclePositionEventProducer.publishBatch(events);

            return new NtaVehiclePositionIngestionResponse(
                    feedTimestamp,
                    scannedVehicleCount,
                    events.size(),
                    skippedVehicleCount,
                    List.copyOf(events)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA Vehicles API returned invalid JSON"
            );
        }
    }

    /**
     * Keeps the automatic scheduler's intent explicit at its call site.
     */
    public NtaVehiclePositionIngestionResponse publishAllVehiclePositions() {
        return publishVehiclePositions(DEFAULT_MAX_EVENTS);
    }

    private VehiclePositionEvent toEvent(
            JsonNode vehicle,
            Instant feedTimestamp
    ) {
        JsonNode trip = vehicle.path("trip");
        JsonNode position = vehicle.path("position");
        JsonNode vehicleDescriptor = vehicle.path("vehicle");

        String vehicleId = text(vehicleDescriptor, "id");
        Double latitude = number(position, "latitude");
        Double longitude = number(position, "longitude");

        if (isBlank(vehicleId) || !validCoordinates(latitude, longitude)) {
            return null;
        }

        Instant observedAt = timestamp(vehicle, "timestamp", feedTimestamp);
        String externalTripId = text(trip, "trip_id");
        String externalRouteId = text(trip, "route_id");

        return new VehiclePositionEvent(
                deterministicEventId(vehicleId, observedAt, latitude, longitude),
                vehicleId,
                externalTripId,
                externalRouteId,
                text(trip, "start_time"),
                text(trip, "start_date"),
                shortNumber(trip, "direction_id"),
                latitude,
                longitude,
                number(position, "bearing"),
                observedAt
        );
    }

    private Instant feedTimestamp(JsonNode root) {
        return timestamp(root.path("header"), "timestamp", Instant.now());
    }

    private Instant timestamp(
            JsonNode node,
            String fieldName,
            Instant fallback
    ) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return fallback;
        }

        try {
            // NTA currently serializes this GTFS timestamp as a JSON string.
            long seconds = Long.parseLong(value.asText());
            return seconds > 0 ? Instant.ofEpochSecond(seconds) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Double number(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() || !value.isNumber()
                ? null
                : value.asDouble();
    }

    private Short shortNumber(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() || !value.canConvertToInt()
                ? null
                : (short) value.asInt();
    }

    private boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validateLimit(int maxEvents) {
        if (maxEvents < 0 || maxEvents > MAX_MANUAL_EVENTS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "limit must be between 0 and " + MAX_MANUAL_EVENTS
            );
        }
    }

    private UUID deterministicEventId(
            String vehicleId,
            Instant observedAt,
            Double latitude,
            Double longitude
    ) {
        String source = vehicleId + "|" + observedAt.toEpochMilli()
                + "|" + latitude + "|" + longitude;

        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
