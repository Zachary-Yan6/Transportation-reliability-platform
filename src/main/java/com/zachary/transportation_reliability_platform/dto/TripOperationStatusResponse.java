package com.zachary.transportation_reliability_platform.dto;

import java.time.LocalDate;

public record TripOperationStatusResponse(
        Long tripId,
        String externalTripId,
        LocalDate serviceDate,
        boolean running,
        String reason
) {
}