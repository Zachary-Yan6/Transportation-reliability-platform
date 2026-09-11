package com.zachary.transportation_reliability_platform.dto;

public record GtfsStopTimeImportResponse(
        Long feedVersionId,
        int importedStopTimeCount
) {
}