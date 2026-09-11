package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.response.RouteTrainingDataStatusResponse;

/**
 * Checks whether historical observations are sufficient
 * for a first delay-prediction baseline.
 */
public interface PredictionReadinessService {

    RouteTrainingDataStatusResponse getRouteTrainingDataStatus(
            Long routeId
    );
}