package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.TripReliabilityResponse;

public interface TripReliabilityService {

    /**
     * Calculates reliability statistics for one Trip.
     */
    TripReliabilityResponse getTripReliability(Long tripId);
}