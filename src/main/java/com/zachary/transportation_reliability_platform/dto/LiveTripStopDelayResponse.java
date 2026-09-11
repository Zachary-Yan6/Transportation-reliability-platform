package com.zachary.transportation_reliability_platform.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The newest known delay for one stop in one active trip.
 *
 * <p>This is short-lived state from Redis, not a replacement for the complete
 * historical observation stored in PostgreSQL.</p>
 */
public record LiveTripStopDelayResponse(
        UUID eventId,
        Long feedVersionId,
        String externalTripId,
        String externalStopId,
        Integer stopSequence,
        Integer delaySeconds,
        Instant observedAt,
        OffsetDateTime cachedAt,
        String source
) {
}
