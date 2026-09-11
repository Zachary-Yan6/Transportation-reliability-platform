package com.zachary.transportation_reliability_platform.dto.response;

import java.time.Instant;

/** Summary returned after the NTA Alerts feed is synchronized. */
public record NtaServiceAlertSyncResponse(
        Instant feedTimestamp, int scannedAlertCount, int savedAlertCount, int resolvedAlertCount
) { }
