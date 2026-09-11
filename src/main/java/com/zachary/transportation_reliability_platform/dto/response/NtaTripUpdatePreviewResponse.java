package com.zachary.transportation_reliability_platform.dto.response;

/**
 * One stop-level delay extracted from an NTA trip update.
 * This is only used to inspect live data before publishing events.
 */
public record NtaTripUpdatePreviewResponse(
        String externalTripId,
        String externalRouteId,
        String externalStopId,
        Integer stopSequence,
        Integer arrivalDelaySeconds,
        Integer departureDelaySeconds
) {
}