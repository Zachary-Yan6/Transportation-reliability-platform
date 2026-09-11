package com.zachary.transportation_reliability_platform.dto;

import java.time.LocalDate;

public record GtfsRouteImportResponse(
        int importedRouteCount,
        String sourceUri,
        LocalDate effectiveFrom
) {
}