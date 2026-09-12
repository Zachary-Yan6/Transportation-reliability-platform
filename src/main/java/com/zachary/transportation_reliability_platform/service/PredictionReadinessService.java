package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.response.RouteTrainingDataStatusResponse;

import java.util.List;

/**
 * Checks whether historical observations are sufficient
 * for a first delay-prediction baseline.
 */
public interface PredictionReadinessService {

    RouteTrainingDataStatusResponse getRouteTrainingDataStatus(
            Long routeId
    );

    /**
     * Finds every active-feed route that satisfies all model-data quality
     * gates. The batch trainer uses this list instead of guessing route IDs.
     */
    List<RouteTrainingDataStatusResponse> getRoutesReadyForTraining();
}
