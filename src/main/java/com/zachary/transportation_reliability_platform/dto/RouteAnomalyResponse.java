package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;

/**
 * Explains whether a route has an unusual recent delay pattern.
 *
 * <p>This is a transparent, rule-based baseline. It can later be replaced
 * or enhanced by a machine-learning anomaly model.</p>
 */
public record RouteAnomalyResponse(
        Long routeId,
        String routeShortName,
        int windowHours,
        Long observationCount,
        BigDecimal averageDelaySeconds,
        BigDecimal p90DelaySeconds,
        boolean anomalyDetected,
        String severity,
        String reason
) {
}
