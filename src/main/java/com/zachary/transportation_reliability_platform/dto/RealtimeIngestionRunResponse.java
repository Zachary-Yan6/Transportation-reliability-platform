package com.zachary.transportation_reliability_platform.dto;

import java.time.OffsetDateTime;

/**
 * One polling run returned to the frontend or monitoring client.
 */
public record RealtimeIngestionRunResponse(
        Long id,
        String targetExternalRouteId,
        OffsetDateTime feedTimestamp,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Integer scannedStopTimeUpdates,
        Integer publishedEventCount,
        Integer skippedUpdateCount,
        String status,
        String errorMessage,
        Long durationMilliseconds
) {
}