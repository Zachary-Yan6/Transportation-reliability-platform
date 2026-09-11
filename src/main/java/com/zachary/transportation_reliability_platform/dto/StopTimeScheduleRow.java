package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StopTimeScheduleRow {

    private Integer stopSequence;
    private String externalStopId;
    private String stopName;
    private Integer arrivalSeconds;
    private Integer departureSeconds;
}