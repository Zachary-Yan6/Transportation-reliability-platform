package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.TripStopDelayEstimateResponse;
import com.zachary.transportation_reliability_platform.service.TripStopDelayEstimateService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Provides one frontend-friendly endpoint for current delay or prediction.
 */
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripStopDelayEstimateController {

    private final TripStopDelayEstimateService delayEstimateService;

    /**
     * Returns live NTA state for the current time when it is fresh; otherwise
     * returns the historical baseline prediction for the requested time.
     */
    @GetMapping("/{tripId}/stops/{stopId}/delay-estimate")
    public TripStopDelayEstimateResponse getDelayEstimate(
            @PathVariable Long tripId,
            @PathVariable Long stopId,
            @RequestParam(required = false) Integer stopSequence,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime targetTime
    ) {
        return delayEstimateService.estimateDelay(
                tripId,
                stopId,
                stopSequence,
                targetTime == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : targetTime
        );
    }

    /**
     * Supplies every scheduled stop in one request for the trip detail page.
     */
    @GetMapping("/{tripId}/delay-estimates")
    public List<TripStopDelayEstimateResponse> getDelayEstimates(
            @PathVariable Long tripId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime targetTime
    ) {
        return delayEstimateService.estimateDelaysForTrip(
                tripId,
                targetTime == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : targetTime
        );
    }
}
