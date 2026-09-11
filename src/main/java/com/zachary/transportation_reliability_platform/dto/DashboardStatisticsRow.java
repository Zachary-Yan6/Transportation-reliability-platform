package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Internal MyBatis projection for dashboard aggregate values.
 */
@Getter
@Setter
@NoArgsConstructor
public class DashboardStatisticsRow {

    private Long observationCount;
    private Long monitoredTripCount;
    private Long monitoredRouteCount;
    private BigDecimal averageDelaySeconds;
    private BigDecimal onTimePercentage;
    private OffsetDateTime latestObservedAt;
}