package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A delay observation returned to the API client.
 */
@Getter
@Setter
@NoArgsConstructor
public class TripDelayObservationResponse {

    private UUID eventId;

    private Long tripId;
    private String externalTripId;

    private Long stopId;
    private String externalStopId;
    private String stopName;

    private Integer stopSequence;
    private Integer delaySeconds;

    private OffsetDateTime observedAt;
    private OffsetDateTime receivedAt;
}