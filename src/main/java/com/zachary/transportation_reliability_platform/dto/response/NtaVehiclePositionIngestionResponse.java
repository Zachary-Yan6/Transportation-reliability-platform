package com.zachary.transportation_reliability_platform.dto.response;

import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of publishing one NTA vehicle-position snapshot to Kafka.
 */
public record NtaVehiclePositionIngestionResponse(
        Instant feedTimestamp,
        int scannedVehicleCount,
        int publishedEventCount,
        int skippedVehicleCount,
        List<VehiclePositionEvent> publishedEvents
) {
    public NtaVehiclePositionIngestionResponse {
        publishedEvents = publishedEvents == null
                ? List.of()
                : new ArrayList<>(publishedEvents);
    }

    @Override
    public List<VehiclePositionEvent> publishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
