package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.*;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.RouteAnomalyDetectionService;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RouteReliabilityService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;
    private final RouteReliabilityService routeReliabilityService;
    private final RouteAnomalyDetectionService routeAnomalyDetectionService;
    private final ServiceAlertService serviceAlertService;
    private final TripService tripService;
    private final FeedVersionService feedVersionService;
    @GetMapping
    public List<Route> getRoutes(@RequestParam(required = false) Long feedVersionId) {
        return routeService.lambdaQuery()
                .eq(Route::getFeedVersionId, resolveFeedVersionId(feedVersionId))
                .list();
    }

    @GetMapping("/{id}")
    public Route getRouteById(@PathVariable("id") Long id) {
        return routeService.getRequiredById(id);
    }

    /**
     * Returns route reliability.
     *
     * Without "hours", it uses all stored observations.
     * With "hours", it uses only observations in that recent time window.
     */
    @GetMapping("/{id}/reliability")
    public RouteReliabilityResponse getRouteReliability(
            @PathVariable("id") Long routeId,
            @RequestParam(required = false) Integer hours
    ) {
        if (hours == null) {
            return routeReliabilityService.getRouteReliability(routeId);
        }

        return routeReliabilityService.getRouteReliability(routeId, hours);
    }

    /**
     * Lists the least reliable routes with observations in the selected period.
     */
    @GetMapping("/reliability/worst")
    public List<RouteReliabilityRankingResponse> getWorstRoutes(
            @RequestParam(required = false) Long feedVersionId,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return routeReliabilityService.getLeastReliableRoutes(
                resolveFeedVersionId(feedVersionId),
                hours,
                limit
        );
    }

    /**
     * Returns every static GTFS trip assigned to one route.
     */
    @GetMapping("/{id}/trips")
    public List<TripResponse> getTripsByRoute(
            @PathVariable("id") Long routeId
    ) {
        // Return 404 if the route does not exist.
        routeService.getRequiredById(routeId);

        return tripService.findByRouteId(routeId);
    }

    /**
     * Returns the stops with the largest average delay on this route.
     */
    @GetMapping("/{id}/stops/delays")
    public List<RouteStopDelayResponse> getMostDelayedStops(
            @PathVariable("id") Long routeId,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return routeReliabilityService.getMostDelayedStops(
                routeId,
                hours,
                limit
        );
    }

    /**
     * Returns hourly delay trend data for one route.
     */
    @GetMapping("/{id}/delay-trend")
    public List<RouteDelayTrendResponse> getRouteDelayTrend(
            @PathVariable("id") Long routeId,
            @RequestParam(defaultValue = "24") int hours
    ) {
        return routeReliabilityService.getDelayTrend(routeId, hours);
    }

    /**
     * Checks whether a route has an unusual delay pattern in a recent window.
     */
    @GetMapping("/{id}/anomaly")
    public RouteAnomalyResponse checkRouteAnomaly(
            @PathVariable("id") Long routeId,
            @RequestParam(defaultValue = "1") int hours
    ) {
        return routeAnomalyDetectionService.checkRoute(routeId, hours);
    }

    /**
     * Evaluates a route and stores, refreshes, or resolves its delay alert.
     */
    @PostMapping("/{id}/anomaly/alerts")
    public RouteAlertEvaluationResponse evaluateRouteAlert(
            @PathVariable("id") Long routeId,
            @RequestParam(defaultValue = "1") int hours
    ) {
        return serviceAlertService.evaluateRouteAnomaly(routeId, hours);
    }

    private Long resolveFeedVersionId(Long requestedFeedVersionId) {
        if (requestedFeedVersionId != null) {
            return requestedFeedVersionId;
        }

        return feedVersionService.findActive()
                .map(feedVersion -> feedVersion.getId())
                .orElseThrow(() -> new IllegalStateException("No active GTFS feed version is available"));
    }
}
