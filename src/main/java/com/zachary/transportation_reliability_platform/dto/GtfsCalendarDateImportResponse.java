package com.zachary.transportation_reliability_platform.dto;

public record GtfsCalendarDateImportResponse(
        Long feedVersionId,
        int importedCalendarDateCount
) {
}