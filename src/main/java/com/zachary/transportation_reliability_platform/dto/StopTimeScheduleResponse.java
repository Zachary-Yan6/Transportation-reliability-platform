package com.zachary.transportation_reliability_platform.dto;

public record StopTimeScheduleResponse(
        Integer stopSequence,
        String stopId,
        String stopName,
        String arrivalTime,
        String departureTime
) {
}