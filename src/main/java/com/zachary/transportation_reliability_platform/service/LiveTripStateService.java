package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;

import java.util.List;

/**
 * Maintains and reads short-lived, latest-known NTA trip state.
 */
public interface LiveTripStateService {

    void update(TripUpdateEvent event);

    List<LiveTripStopDelayResponse> findByTrip(
            Long feedVersionId,
            String externalTripId
    );
}
