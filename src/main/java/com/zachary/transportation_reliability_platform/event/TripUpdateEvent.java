package com.zachary.transportation_reliability_platform.event;

import java.time.Instant;
import java.util.UUID;

public record TripUpdateEvent(

        /**
         * externalTripId   GTFS 原始 trip_id
         * externalStopId   GTFS 原始 stop_id
         * stopSequence     此站在该班次中的顺序
         * delaySeconds     延误秒数；负数表示提前到站
         * observedAt       收到该实时信息的时间
         */
        UUID eventId,
        Long feedVersionId,
        String externalTripId,
        String externalStopId,
        Integer stopSequence,
        Integer delaySeconds,
        Instant observedAt,

        /**
         * Internal IDs resolved from the imported static GTFS feed. They are
         * optional so older Kafka messages and manual test events still work.
         */
        Long resolvedTripId,
        Long resolvedStopId
) {

    /**
     * Compatibility constructor for manual events and messages created before
     * the ingestion pipeline started resolving static GTFS IDs upstream.
     */
    public TripUpdateEvent(
            UUID eventId,
            Long feedVersionId,
            String externalTripId,
            String externalStopId,
            Integer stopSequence,
            Integer delaySeconds,
            Instant observedAt
    ) {
        this(
                eventId,
                feedVersionId,
                externalTripId,
                externalStopId,
                stopSequence,
                delaySeconds,
                observedAt,
                null,
                null
        );
    }
}
