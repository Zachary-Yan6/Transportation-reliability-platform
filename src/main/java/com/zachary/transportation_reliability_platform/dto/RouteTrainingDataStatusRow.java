package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Raw training-data statistics returned from PostgreSQL.
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteTrainingDataStatusRow {

    private Long routeId;
    private String routeShortName;

    private Long observationCount;
    private Long uniqueTripCount;
    private Long uniqueStopCount;

    private OffsetDateTime earliestObservedAt;
    private OffsetDateTime latestObservedAt;

    private BigDecimal coverageHours;
    // Number of distinct hours that actually contain observations.
    private Long activeHourCount;
}