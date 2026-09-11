package com.zachary.transportation_reliability_platform.dto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** A dashboard-friendly representation of an official NTA service alert. */
public record NtaServiceAlertResponse(
        Long id, String externalAlertId, String headerText, String descriptionText,
        String cause, String effect, String severityLevel,
        OffsetDateTime activeFrom, OffsetDateTime activeUntil,
        List<String> externalRouteIds, List<String> externalStopIds,
        String status, OffsetDateTime lastSeenAt
) {
    public NtaServiceAlertResponse {
        externalRouteIds = externalRouteIds == null
                ? List.of()
                : new ArrayList<>(externalRouteIds);
        externalStopIds = externalStopIds == null
                ? List.of()
                : new ArrayList<>(externalStopIds);
    }

    @Override
    public List<String> externalRouteIds() {
        return List.copyOf(externalRouteIds);
    }

    @Override
    public List<String> externalStopIds() {
        return List.copyOf(externalStopIds);
    }
}
