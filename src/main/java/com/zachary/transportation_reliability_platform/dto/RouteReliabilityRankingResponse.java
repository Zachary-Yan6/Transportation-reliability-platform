package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One route in the reliability ranking list.
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteReliabilityRankingResponse {

    private Long routeId;
    private String externalRouteId;
    private String routeShortName;
    private String routeLongName;
    private Long observationCount;
    private BigDecimal averageDelaySeconds;
    private BigDecimal reliabilityScore;
}