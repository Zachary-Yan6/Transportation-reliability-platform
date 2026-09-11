package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;

/**
 * Reliability information returned to the API client.
 */
public record TripReliabilityResponse(
        Long tripId,
        String externalTripId,
        Long observationCount,
        BigDecimal averageDelaySeconds,
        Integer maximumDelaySeconds,
        BigDecimal p90DelaySeconds,
        BigDecimal onTimePercentage,
        BigDecimal reliabilityScore,
        String confidence
) {
}