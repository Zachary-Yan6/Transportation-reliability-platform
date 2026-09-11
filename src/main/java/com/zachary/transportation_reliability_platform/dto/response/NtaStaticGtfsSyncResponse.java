package com.zachary.transportation_reliability_platform.dto.response;

import java.time.LocalDate;

/** The result of comparing, importing, and optionally activating a static feed. */
public record NtaStaticGtfsSyncResponse(
        String status,
        Long feedVersionId,
        String checksum,
        LocalDate effectiveFrom,
        int routeCount,
        int stopCount,
        int tripCount,
        int stopTimeCount,
        int calendarCount,
        int calendarDateCount
) { }
