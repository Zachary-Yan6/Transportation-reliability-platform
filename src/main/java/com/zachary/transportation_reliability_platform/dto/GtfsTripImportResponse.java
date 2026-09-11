package com.zachary.transportation_reliability_platform.dto;

public record GtfsTripImportResponse(
        Long feedVersionId,
        int importedTripCount
) {
}