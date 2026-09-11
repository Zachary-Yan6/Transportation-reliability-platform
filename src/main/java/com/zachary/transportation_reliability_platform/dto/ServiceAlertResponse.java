package com.zachary.transportation_reliability_platform.dto;

import java.time.OffsetDateTime;

/**
 * An alert returned by the Service Alerts API.
 */
public record ServiceAlertResponse(
        Long id,
        Long routeId,
        String alertType,
        String severity,
        String status,
        String message,
        OffsetDateTime firstDetectedAt,
        OffsetDateTime lastDetectedAt,
        OffsetDateTime resolvedAt
) {
}
