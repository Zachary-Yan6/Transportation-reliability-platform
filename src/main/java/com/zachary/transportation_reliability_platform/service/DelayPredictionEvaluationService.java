package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.DelayPredictionEvaluationResponse;

/**
 * Replays historical observations to measure baseline prediction quality.
 */
public interface DelayPredictionEvaluationService {

    DelayPredictionEvaluationResponse evaluateRouteBaseline(
            Long routeId,
            int sampleLimit
    );
}
