package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;

import java.time.OffsetDateTime;

/**
 * Provides the first, explainable delay-prediction baseline.
 */
public interface DelayPredictionService {

    DelayPredictionResponse predictDelay(
            Long routeId,
            Long stopId,
            OffsetDateTime targetTime
    );
}
