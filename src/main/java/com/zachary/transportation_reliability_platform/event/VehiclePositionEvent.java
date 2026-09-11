package com.zachary.transportation_reliability_platform.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A normalized GPS observation from NTA's GTFS-Realtime Vehicles operation.
 *
 * <p>The NTA feed entity ID (for example, {@code V1}) is intentionally not
 * included: it identifies a feed item, not a stable physical vehicle. The
 * nested {@code vehicle.id} is used as {@code externalVehicleId} instead.</p>
 */
public record VehiclePositionEvent(
        UUID eventId,
        String externalVehicleId,
        String externalTripId,
        String externalRouteId,
        String tripStartTime,
        String tripStartDate,
        Short directionId,
        Double latitude,
        Double longitude,
        Double bearing,
        Instant observedAt
) {
}
