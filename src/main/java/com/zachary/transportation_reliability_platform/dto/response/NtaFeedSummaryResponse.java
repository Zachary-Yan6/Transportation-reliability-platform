package com.zachary.transportation_reliability_platform.dto.response;

import java.time.Instant;

/**
 * A small summary of the live NTA GTFS-Realtime response.
 * We use this before mapping the full feed into our domain events.
 */
public record NtaFeedSummaryResponse(
        Instant feedTimestamp,
        int totalEntities,
        int tripUpdateCount,
        int vehiclePositionCount,
        int alertCount
) {
}