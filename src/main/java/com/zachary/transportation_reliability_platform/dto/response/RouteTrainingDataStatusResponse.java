package com.zachary.transportation_reliability_platform.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Explains whether one route has enough historical data
 * for the first delay-prediction baseline.
 */
public record RouteTrainingDataStatusResponse(
        Long routeId,
        String routeShortName,
        Long observationCount,
        Long uniqueTripCount,
        Long uniqueStopCount,
        OffsetDateTime earliestObservedAt,
        OffsetDateTime latestObservedAt,
        BigDecimal coverageHours,
        Long activeHourCount,
        long minimumObservationCount,
        int minimumCoverageHours,
        int minimumActiveHours,
        boolean readyForBaseline,
        String recommendation
) {
}