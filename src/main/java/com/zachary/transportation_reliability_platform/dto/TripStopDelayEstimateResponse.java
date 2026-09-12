package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * One delay value selected from the most appropriate available source.
 *
 * <p>For a request about the present, {@code state=LIVE} means that the
 * value came from the short-lived NTA state in Redis. For a future request,
 * or when live state is unavailable, {@code state=PREDICTED} means that a
 * promoted trained model or the historical baseline was used instead.
 * {@code state=UNAVAILABLE} means that neither source has a value yet.</p>
 */
public record TripStopDelayEstimateResponse(
        Long tripId,
        Long routeId,
        Long stopId,
        String externalStopId,
        String stopName,
        Integer stopSequence,
        OffsetDateTime targetTime,
        BigDecimal estimatedDelaySeconds,
        String state,
        String source,
        String confidence,
        Instant observedAt,
        Long matchedSampleCount,
        BigDecimal p90DelaySeconds,
        String modelVersion,
        String explanation
) {
}
