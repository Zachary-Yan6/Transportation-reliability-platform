package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.TripStopDelayEstimateResponse;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Selects the best available delay source for one scheduled stop: live NTA
 * state first, then an eligible trained model, then the historical baseline.
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
