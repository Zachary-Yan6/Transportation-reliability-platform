package com.zachary.transportation_reliability_platform.dto;

public record GtfsCalendarImportResponse(
        Long feedVersionId,
        int importedCalendarCount
) {
}