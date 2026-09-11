package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * An explainable baseline prediction for delay at one route stop.
 */
public record DelayPredictionResponse(
        Long routeId,
        Long stopId,
        OffsetDateTime targetTime,
        int targetDayOfWeek,
        int targetHour,
        BigDecimal predictedDelaySeconds,
        BigDecimal p90DelaySeconds,
        Long matchedSampleCount,
        String dataSource,
        String confidence,
        String modelVersion,
        String explanation
) {
}
