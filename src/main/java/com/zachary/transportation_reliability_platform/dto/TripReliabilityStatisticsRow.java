package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Internal projection populated by the MyBatis aggregate query.
 * This class is not returned directly by the Controller.
 */
@Getter
@Setter
@NoArgsConstructor
public class TripReliabilityStatisticsRow {

    private Long observationCount;
    private BigDecimal averageDelaySeconds;
    private Integer maximumDelaySeconds;
    private BigDecimal p90DelaySeconds;
    private BigDecimal onTimePercentage;

}