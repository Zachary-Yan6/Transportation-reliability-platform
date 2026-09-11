package com.zachary.transportation_reliability_platform.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A static GTFS stop-time row that can be matched to an incoming
 * GTFS-Realtime stop update.
 *
 * <p>The external identifiers are needed while parsing NTA data. The internal
 * identifiers are included in the Kafka event so the consumer can write an
 * observation without repeating the same database lookups.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class RealtimeStopTimeMatchRow {

    private Long tripId;
    private Long stopId;

    private String externalTripId;
    private String externalStopId;
    private Integer stopSequence;
}
