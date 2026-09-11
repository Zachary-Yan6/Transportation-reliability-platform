package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;

/**
 * Route-level reliability information returned by the API.
 */
public record RouteReliabilityResponse(
        Long routeId,
        String externalRouteId,
        String routeShortName,
        String routeLongName,
        Long observationCount,
        BigDecimal averageDelaySeconds,
        Integer maximumDelaySeconds,
        BigDecimal p90DelaySeconds,
        BigDecimal onTimePercentage,
        BigDecimal reliabilityScore,
        String confidence
) {
}