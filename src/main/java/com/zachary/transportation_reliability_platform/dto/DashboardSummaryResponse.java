package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Overall live reliability summary for one GTFS feed version.
 */
public record DashboardSummaryResponse(
        Long feedVersionId,
        int hours,
        Long observationCount,
        Long monitoredTripCount,
        Long monitoredRouteCount,
        BigDecimal averageDelaySeconds,
        BigDecimal onTimePercentage,
        OffsetDateTime latestObservedAt,
        String confidence
) {
}