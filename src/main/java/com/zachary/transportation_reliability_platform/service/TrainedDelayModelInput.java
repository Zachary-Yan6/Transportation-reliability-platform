package com.zachary.transportation_reliability_platform.service;

import java.time.OffsetDateTime;

/** Features known at prediction time for one scheduled trip stop. */
public record TrainedDelayModelInput(
        Long stopId,
        Integer stopSequence,
        Integer scheduledArrivalSeconds,
        Integer scheduledDepartureSeconds,
        OffsetDateTime targetTime
) {
}
