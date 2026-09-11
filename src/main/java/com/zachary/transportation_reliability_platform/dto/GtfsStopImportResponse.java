package com.zachary.transportation_reliability_platform.dto;

public record GtfsStopImportResponse(
        Long feedVersionId,
        int importedStopCount
) {
}