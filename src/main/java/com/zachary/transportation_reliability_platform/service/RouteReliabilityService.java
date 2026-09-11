package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.RouteDelayTrendResponse;
import com.zachary.transportation_reliability_platform.dto.RouteReliabilityRankingResponse;
import com.zachary.transportation_reliability_platform.dto.RouteReliabilityResponse;
import com.zachary.transportation_reliability_platform.dto.RouteStopDelayResponse;

import java.util.List;

public interface RouteReliabilityService {

    /**
     * Calculates reliability from all observed trips on one route.
     */
    RouteReliabilityResponse getRouteReliability(Long routeId);

    List<RouteReliabilityRankingResponse> getLeastReliableRoutes(
            Long feedVersionId,
            int hours,
            int limit
    );

    List<RouteStopDelayResponse> getMostDelayedStops(
            Long routeId,
            int hours,
            int limit
    );

    /**
     * Returns hourly delay statistics for one route within a time window.
     */
    List<RouteDelayTrendResponse> getDelayTrend(
            Long routeId,
            int hours
    );

    /**
     * Calculates route reliability using observations from a selected period.
     */
    RouteReliabilityResponse getRouteReliability(
            Long routeId,
            int hours
    );
}