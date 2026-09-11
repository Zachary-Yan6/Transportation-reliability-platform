package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.TripStopDelayEstimateResponse;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Selects a live delay or a historical prediction for one scheduled stop.
 */
public interface TripStopDelayEstimateService {

    TripStopDelayEstimateResponse estimateDelay(
            Long tripId,
            Long stopId,
            Integer stopSequence,
            OffsetDateTime targetTime
    );

    /**
     * Produces one live-or-predicted delay value for every scheduled stop in
     * a trip. The dashboard uses this batch operation to avoid one HTTP call
     * per stop.
     */
    List<TripStopDelayEstimateResponse> estimateDelaysForTrip(
            Long tripId,
            OffsetDateTime targetTime
    );
}
