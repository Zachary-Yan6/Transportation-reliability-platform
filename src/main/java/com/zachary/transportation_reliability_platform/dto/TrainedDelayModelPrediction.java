package com.zachary.transportation_reliability_platform.dto;

import java.math.BigDecimal;

/** A prediction returned by a promoted route-specific trained model. */
public record TrainedDelayModelPrediction(
        BigDecimal predictedDelaySeconds,
        Long trainingSampleCount,
        String modelVersion,
        String confidence,
        String explanation
) {
}
