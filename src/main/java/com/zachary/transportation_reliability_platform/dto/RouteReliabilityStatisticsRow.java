package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Internal MyBatis projection for one route's aggregate delay statistics.
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteReliabilityStatisticsRow {

    private Long observationCount;
    private BigDecimal averageDelaySeconds;
    private Integer maximumDelaySeconds;
    private BigDecimal p90DelaySeconds;
    private BigDecimal onTimePercentage;
}