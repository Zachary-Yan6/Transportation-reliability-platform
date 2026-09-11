package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Quality metrics from replaying the baseline against historical GTFS-Realtime
 * observations that were not used as the initial warm-up period.
 */
public record DelayPredictionEvaluationResponse(
        Long routeId,
        int candidateSampleCount,
        int evaluatedSampleCount,
        int skippedSampleCount,
        int sameWeekdayHourPredictionCount,
        int stopHistoryPredictionCount,
        int routeFallbackPredictionCount,
        OffsetDateTime firstEvaluationSampleAt,
        OffsetDateTime lastEvaluationSampleAt,
        BigDecimal meanAbsoluteErrorSeconds,
        BigDecimal rootMeanSquaredErrorSeconds,
        BigDecimal meanActualDelaySeconds,
        BigDecimal meanPredictedDelaySeconds,
        BigDecimal meanSignedPredictionBiasSeconds,
        BigDecimal withinFiveMinutesPercentage,
        String modelVersion,
        String recommendation
) {
}
