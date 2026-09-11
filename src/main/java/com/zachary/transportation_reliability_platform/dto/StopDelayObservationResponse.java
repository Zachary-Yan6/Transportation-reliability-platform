package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One live delay event observed at a specific stop.
 * It also includes the trip and route that produced the observation.
 */
@Getter
@Setter
@NoArgsConstructor
public class StopDelayObservationResponse {

    private UUID eventId;

    private Long tripId;
    private String externalTripId;

    private Long routeId;
    private String externalRouteId;
    private String routeShortName;
    private String routeLongName;

    private Integer stopSequence;
    private Integer delaySeconds;

    private OffsetDateTime observedAt;
    private OffsetDateTime receivedAt;
}