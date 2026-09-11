package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.StopDelayObservationResponse;
import com.zachary.transportation_reliability_platform.dto.TripDelayObservationResponse;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;

import java.util.List;
import java.util.UUID;

public interface TripStopDelayObservationService
        extends IService<TripStopDelayObservation> {

    boolean existsByEventId(UUID eventId);

    /**
     * Saves an observation exactly once, even when Kafka delivers an event
     * more than once.
     *
     * @return true when a new row was inserted; false for a duplicate event
     */
    boolean saveIfAbsent(TripStopDelayObservation observation);

    List<TripDelayObservationResponse> findRecentByTripId(
            Long tripId,
            int limit
    );

    List<StopDelayObservationResponse> findRecentByStopId(
            Long stopId,
            int limit
    );
}
