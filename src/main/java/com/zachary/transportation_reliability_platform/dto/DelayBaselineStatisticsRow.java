package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Internal projection for a group of historical delay observations.
 */
@Getter
@Setter
@NoArgsConstructor
public class DelayBaselineStatisticsRow {

    private Long sampleCount;
    private BigDecimal averageDelaySeconds;
    private BigDecimal p90DelaySeconds;
}
