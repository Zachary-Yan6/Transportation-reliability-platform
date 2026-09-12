package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.DelayBaselineStatisticsRow;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.DelayPredictionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.StopService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A hierarchical historical-average predictor.
 *
 * <p>It first looks for the same route, stop, weekday and hour. If that
 * sample is too small, it falls back to all history for the stop and then to
 * route-wide history. A future trained model can keep this API unchanged.</p>
 */
@Service
@RequiredArgsConstructor
public class DelayPredictionServiceImpl
        implements DelayPredictionService {

    private static final int MIN_TIME_MATCHED_SAMPLES = 5;
    private static final int MIN_STOP_HISTORY_SAMPLES = 3;
    private static final String MODEL_VERSION =
            "HISTORICAL_AVERAGE_BASELINE_V1";
    private static final ZoneId DUBLIN_TIME_ZONE =
            ZoneId.of("Europe/Dublin");

    private final RouteService routeService;
    private final StopService stopService;
    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public DelayPredictionResponse predictDelay(
            Long routeId,
            Long stopId,
            OffsetDateTime targetTime
    ) {
        // Return 404s for invalid IDs instead of silently producing a fallback.
        routeService.getRequiredById(routeId);
        stopService.getRequiredById(stopId);

        // convert to specific zone time
        ZonedDateTime localTargetTime = targetTime
                .atZoneSameInstant(DUBLIN_TIME_ZONE);


        int dayOfWeek = localTargetTime.getDayOfWeek().getValue();
        int hour = localTargetTime.getHour();

        // return delay of a stop in a day of week at a specific time
        /**
         * sampleCount;
         * averageDelaySeconds;
         * p90DelaySeconds;
         */
        DelayBaselineStatisticsRow timeMatched =
                delayObservationMapper.findTimeMatchedStopBaseline(
                        routeId,
                        stopId,
                        dayOfWeek,
                        hour,
                        targetTime
                );

        // prediction has to have enough observation
        if (hasAtLeast(timeMatched, MIN_TIME_MATCHED_SAMPLES)) {
            return predictionFromBaseline(
                    routeId,
                    stopId,
                    targetTime,
                    dayOfWeek,
                    hour,
                    timeMatched,
                    "STOP_SAME_WEEKDAY_HOUR",
                    "Historical observations from this stop at the same "
                            + "weekday and hour were used."
            );
        }

        // return general delay of a stop before some time
        DelayBaselineStatisticsRow stopHistory =
                delayObservationMapper.findStopHistoryBaseline(
                        routeId,
                        stopId,
                        targetTime
                );

        if (hasAtLeast(stopHistory, MIN_STOP_HISTORY_SAMPLES)) {
            return predictionFromBaseline(
                    routeId,
                    stopId,
                    targetTime,
                    dayOfWeek,
                    hour,
                    stopHistory,
                    "STOP_ALL_HOURS",
                    "Not enough time-matched samples exist, so all "
                            + "historical observations for this stop were used."
            );
        }

        // return general delay of a route before some time
        DelayBaselineStatisticsRow routeHistory =
                delayObservationMapper.findRouteHistoryBaseline(
                        routeId,
                        targetTime
                );

        if (hasAtLeast(routeHistory, 1)) {
            return predictionFromBaseline(
                    routeId,
                    stopId,
                    targetTime,
                    dayOfWeek,
                    hour,
                    routeHistory,
                    "ROUTE_ALL_STOPS",
                    "Not enough stop-specific history exists, so route-wide "
                            + "historical delay was used."
            );
        }

        return new DelayPredictionResponse(
                routeId,
                stopId,
                targetTime,
                dayOfWeek,
                hour,
                null,
                null,
                0L,
                "NO_DATA",
                "NO_DATA",
                MODEL_VERSION,
                "No historical delay observations are available for this route."
        );
    }

    private boolean hasAtLeast(
            DelayBaselineStatisticsRow baseline,
            int minimumSamples
    ) {
        return baseline != null
                && baseline.getSampleCount() != null
                && baseline.getSampleCount() >= minimumSamples;
    }

    private DelayPredictionResponse predictionFromBaseline(
            Long routeId,
            Long stopId,
            OffsetDateTime targetTime,
            int dayOfWeek,
            int hour,
            DelayBaselineStatisticsRow baseline,
            String dataSource,
            String explanation
    ) {
        return new DelayPredictionResponse(
                routeId,
                stopId,
                targetTime,
                dayOfWeek,
                hour,
                baseline.getAverageDelaySeconds(),
                baseline.getP90DelaySeconds(),
                baseline.getSampleCount(),
                dataSource,
                confidenceFor(baseline.getSampleCount()),
                MODEL_VERSION,
                explanation
        );
    }

    private String confidenceFor(Long sampleCount) {
        if (sampleCount >= 50) {
            return "HIGH";
        }

        if (sampleCount >= 20) {
            return "MEDIUM";
        }

        return "LOW";
    }
}
