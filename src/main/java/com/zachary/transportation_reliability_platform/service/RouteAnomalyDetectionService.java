package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;

/**
 * Detects unusual delay conditions on a route.
 */
public interface RouteAnomalyDetectionService {

    RouteAnomalyResponse checkRoute(
            Long routeId,
            int hours
    );
}
