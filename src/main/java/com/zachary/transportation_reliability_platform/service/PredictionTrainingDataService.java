package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.RouteDelayTrainingSampleResponse;

import java.util.List;

/**
 * Produces labelled GTFS-Realtime samples for delay-prediction experiments.
 */
public interface PredictionTrainingDataService {

    List<RouteDelayTrainingSampleResponse> getRouteTrainingSamples(
            Long routeId,
            int limit
    );

    /**
     * Creates a CSV dataset that can be saved locally and passed to a model
     * training notebook or Python script.
     */
    String exportRouteTrainingSamplesCsv(Long routeId, int limit);
}
