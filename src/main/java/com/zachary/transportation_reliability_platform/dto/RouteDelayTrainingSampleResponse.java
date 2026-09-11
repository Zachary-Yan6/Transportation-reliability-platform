package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One labelled, prediction-ready delay sample.
 *
 * <p>The scheduled GTFS fields and local time fields are model features.
 * {@code actualDelaySeconds} is the historical label a future model learns
 * to predict.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteDelayTrainingSampleResponse {

    private UUID eventId;

    private Long routeId;
    private Long tripId;
    private String externalTripId;

    private Long stopId;
    private String externalStopId;
    private Integer stopSequence;

    private Integer scheduledArrivalSeconds;
    private Integer scheduledDepartureSeconds;

    private Integer observedDayOfWeek;
    private Integer observedHour;
    private OffsetDateTime observedAt;

    // This is the model label: the real delay reported by NTA.
    private Integer actualDelaySeconds;
}
