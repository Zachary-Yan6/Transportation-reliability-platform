package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One stop with aggregated delay statistics for a selected route.
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteStopDelayResponse {

    private Long stopId;
    private String externalStopId;
    private String stopName;
    private BigDecimal latitude;
    private BigDecimal longitude;

    private Long observationCount;
    private BigDecimal averageDelaySeconds;
    private Integer maximumDelaySeconds;
    private BigDecimal onTimePercentage;
}