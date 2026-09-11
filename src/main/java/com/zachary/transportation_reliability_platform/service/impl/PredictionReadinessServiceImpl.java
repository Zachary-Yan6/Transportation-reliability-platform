package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.RouteTrainingDataStatusRow;
import com.zachary.transportation_reliability_platform.dto.response.RouteTrainingDataStatusResponse;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.PredictionReadinessService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PredictionReadinessServiceImpl
        implements PredictionReadinessService {

    // Project-level quality gates for the first baseline model.
    private static final long MINIMUM_OBSERVATION_COUNT = 1_000;
    private static final int MINIMUM_COVERAGE_HOURS = 72;
    private static final int MINIMUM_ACTIVE_HOURS = 48;

    private final RouteService routeService;
    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public RouteTrainingDataStatusResponse getRouteTrainingDataStatus(
            Long routeId
    ) {
        // Return 404 if the route does not exist.
        routeService.getRequiredById(routeId);

        RouteTrainingDataStatusRow statistics =
                delayObservationMapper.getTrainingDataStatus(routeId);

        boolean enoughObservations =
                statistics.getObservationCount()
                        >= MINIMUM_OBSERVATION_COUNT;

        boolean enoughTimeCoverage =
                statistics.getCoverageHours()
                        .compareTo(
                                BigDecimal.valueOf(MINIMUM_COVERAGE_HOURS)
                        ) >= 0;

        boolean enoughActiveHours =
                statistics.getActiveHourCount()
                        >= MINIMUM_ACTIVE_HOURS;

        boolean ready = enoughObservations
                && enoughTimeCoverage
                && enoughActiveHours;

        String recommendation = ready
                ? "The route has enough data for a baseline delay-prediction model."
                : "Continue collecting Route "
                + statistics.getRouteShortName()
                + ". Current data: "
                + statistics.getObservationCount()
                + " observations across "
                + statistics.getActiveHourCount()
                + " active hours. Required: at least "
                + MINIMUM_OBSERVATION_COUNT
                + " observations, "
                + MINIMUM_COVERAGE_HOURS
                + " hours of time coverage, and "
                + MINIMUM_ACTIVE_HOURS
                + " active data hours.";

        return new RouteTrainingDataStatusResponse(
                statistics.getRouteId(),
                statistics.getRouteShortName(),
                statistics.getObservationCount(),
                statistics.getUniqueTripCount(),
                statistics.getUniqueStopCount(),
                statistics.getEarliestObservedAt(),
                statistics.getLatestObservedAt(),
                statistics.getCoverageHours(),
                statistics.getActiveHourCount(),
                MINIMUM_OBSERVATION_COUNT,
                MINIMUM_COVERAGE_HOURS,
                MINIMUM_ACTIVE_HOURS,
                ready,
                recommendation
        );
    }
}