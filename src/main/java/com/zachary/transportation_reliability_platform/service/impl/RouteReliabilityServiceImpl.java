package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.*;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.RouteReliabilityService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RouteReliabilityServiceImpl
        implements RouteReliabilityService {

    private final RouteService routeService;
    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public RouteReliabilityResponse getRouteReliability(Long routeId) {
        // Ensures the route exists before calculating statistics.
        Route route = routeService.getRequiredById(routeId);

        // PostgreSQL calculates the aggregate values efficiently.
        RouteReliabilityStatisticsRow statistics =
                delayObservationMapper.calculateReliabilityForRoute(routeId);

        return buildReliabilityResponse(route, statistics);
    }

    @Override
    @Transactional(readOnly = true)
    public RouteReliabilityResponse getRouteReliability(
            Long routeId,
            int hours
    ) {
        // Return 404 if the route does not exist.
        Route route = routeService.getRequiredById(routeId);

        // The API supports a range from one hour to seven days.
        int safeHours = Math.min(Math.max(hours, 1), 168);

        RouteReliabilityStatisticsRow statistics =
                delayObservationMapper.calculateReliabilityForRouteInWindow(
                        routeId,
                        safeHours
                );

        return buildReliabilityResponse(route, statistics);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteReliabilityRankingResponse> getLeastReliableRoutes(
            Long feedVersionId,
            int hours,
            int limit
    ) {
        // Prevent accidentally expensive or meaningless queries.
        int safeHours = Math.min(Math.max(hours, 1), 168);
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        return delayObservationMapper.findLeastReliableRoutes(
                feedVersionId,
                safeHours,
                safeLimit
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteStopDelayResponse> getMostDelayedStops(
            Long routeId,
            int hours,
            int limit
    ) {
        // Return 404 if this route does not exist.
        routeService.getRequiredById(routeId);

        int safeHours = Math.min(Math.max(hours, 1), 168);
        int safeLimit = Math.min(Math.max(limit, 1), 20);

        return delayObservationMapper.findMostDelayedStops(
                routeId,
                safeHours,
                safeLimit
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteDelayTrendResponse> getDelayTrend(
            Long routeId,
            int hours
    ) {
        // Return 404 if the route does not exist.
        routeService.getRequiredById(routeId);

        // Restrict the time window to one hour through seven days.
        int safeHours = Math.min(Math.max(hours, 1), 168);

        return delayObservationMapper.findDelayTrendByRouteId(
                routeId,
                safeHours
        );
    }
    /**
     * Indicates whether the current sample size is sufficient to trust the score.
     */
    private String calculateConfidence(Long observationCount) {
        if (observationCount == null || observationCount == 0) {
            return "NO_DATA";
        }

        if (observationCount < 10) {
            return "LOW";
        }

        if (observationCount < 50) {
            return "MEDIUM";
        }

        return "HIGH";
    }

    /**
     * Converts PostgreSQL aggregate values into the API response format.
     */
    private RouteReliabilityResponse buildReliabilityResponse(
            Route route,
            RouteReliabilityStatisticsRow statistics
    ) {
        return new RouteReliabilityResponse(
                route.getId(),
                route.getExternalRouteId(),
                route.getShortName(),
                route.getLongName(),
                statistics.getObservationCount(),
                statistics.getAverageDelaySeconds(),
                statistics.getMaximumDelaySeconds(),
                statistics.getP90DelaySeconds(),
                statistics.getOnTimePercentage(),
                statistics.getOnTimePercentage(),
                calculateConfidence(statistics.getObservationCount())
        );
    }


}