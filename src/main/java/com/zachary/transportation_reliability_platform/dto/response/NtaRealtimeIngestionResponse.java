package com.zachary.transportation_reliability_platform.dto.response;

import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of one controlled import from the live NTA feed to Kafka.
 */
public record NtaRealtimeIngestionResponse(
        Instant feedTimestamp,
        int scannedStopTimeUpdates,
        int publishedEventCount,
        int skippedUpdateCount,
        List<TripUpdateEvent> publishedEvents
) {
    public NtaRealtimeIngestionResponse {
        publishedEvents = publishedEvents == null
                ? List.of()
                : new ArrayList<>(publishedEvents);
    }

    @Override
    public List<TripUpdateEvent> publishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
