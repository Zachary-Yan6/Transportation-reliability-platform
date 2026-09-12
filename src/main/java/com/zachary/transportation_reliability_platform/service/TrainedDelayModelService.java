package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.TrainedDelayModelPrediction;

import java.util.Optional;

/** Selects a validated trained model only when it is suitable for a stop. */
public interface TrainedDelayModelService {

    Optional<TrainedDelayModelPrediction> predictIfEligible(
            Long routeId,
            TrainedDelayModelInput input
    );
}
