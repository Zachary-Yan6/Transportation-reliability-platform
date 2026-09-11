package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Aggregated delay statistics for one hourly period on one route.
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteDelayTrendResponse {

    // Beginning of this one-hour time bucket.
    private OffsetDateTime bucketStart;

    // Number of stop-delay observations within this hour.
    private Long observationCount;

    // Mean delay across all observations within this hour.
    private BigDecimal averageDelaySeconds;

    // Percentage of observations considered on time.
    private BigDecimal onTimePercentage;
}