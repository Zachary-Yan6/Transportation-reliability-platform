package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionEvaluationResponse;
import com.zachary.transportation_reliability_platform.service.DelayPredictionEvaluationService;
import com.zachary.transportation_reliability_platform.service.DelayPredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Provides explainable baseline delay predictions.
 */
@RestController
@RequestMapping("/api/v1/ai/routes")
@RequiredArgsConstructor
public class DelayPredictionController {

    private final DelayPredictionService delayPredictionService;
    private final DelayPredictionEvaluationService delayPredictionEvaluationService;

    /**
     * Predicts delay at a selected route stop. If no time is supplied, the
     * current time is used as the prediction target.
     */
    @GetMapping("/{routeId}/stops/{stopId}/delay-prediction")
    public DelayPredictionResponse predictDelay(
            @PathVariable Long routeId,
            @PathVariable Long stopId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime targetTime
    ) {
        OffsetDateTime effectiveTargetTime = targetTime == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : targetTime;

        return delayPredictionService.predictDelay(
                routeId,
                stopId,
                effectiveTargetTime
        );
    }

    /**
     * Backtests the baseline using later observations as evaluation examples.
     */
    @GetMapping("/{routeId}/delay-prediction/evaluation")
    public DelayPredictionEvaluationResponse evaluateBaseline(
            @PathVariable Long routeId,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return delayPredictionEvaluationService.evaluateRouteBaseline(
                routeId,
                limit
        );
    }
}
