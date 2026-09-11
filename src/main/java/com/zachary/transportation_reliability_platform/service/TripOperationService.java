package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.TripOperationStatusResponse;

import java.time.LocalDate;

public interface TripOperationService {

    TripOperationStatusResponse getOperationStatus(
            Long tripId,
            LocalDate serviceDate
    );
}