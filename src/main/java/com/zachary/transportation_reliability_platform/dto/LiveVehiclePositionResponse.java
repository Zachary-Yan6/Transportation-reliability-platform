package com.zachary.transportation_reliability_platform.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The most recently observed position of one active vehicle.
 */
public record LiveVehiclePositionResponse(
        UUID eventId,
        String vehicleId,
        String externalTripId,
        String externalRouteId,
        String tripStartTime,
        String tripStartDate,
        Short directionId,
        Double latitude,
        Double longitude,
        Double bearing,
        Instant observedAt,
        OffsetDateTime cachedAt,
        String source
) {
}
