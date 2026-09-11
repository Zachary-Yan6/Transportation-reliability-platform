package com.zachary.transportation_reliability_platform.dto;

/**
 * Contains the detector result and the alert created, refreshed, or resolved
 * because of that result.
 */
public record RouteAlertEvaluationResponse(
        RouteAnomalyResponse anomaly,
        ServiceAlertResponse alert
) {
}
