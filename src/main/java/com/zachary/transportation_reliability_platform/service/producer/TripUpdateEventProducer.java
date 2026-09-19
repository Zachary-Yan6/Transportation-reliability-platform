package com.zachary.transportation_reliability_platform.service.producer;

import com.zachary.transportation_reliability_platform.dto.PublishTripUpdateRequest;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.KafkaOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripUpdateEventProducer {

    public static final String TOPIC = "trip-update-events";

    private final KafkaOutboxService kafkaOutboxService;

    /**
     * Creates and publishes an event submitted through the manual API endpoint.
     */
    public TripUpdateEvent publish(PublishTripUpdateRequest request) {
        TripUpdateEvent event = new TripUpdateEvent(
                UUID.randomUUID(),
                request.feedVersionId(),
                request.externalTripId(),
                request.externalStopId(),
                request.stopSequence(),
                request.delaySeconds(),
                Instant.now()
        );

        return publish(event);
    }

    /**
     * Publishes an event that was created from the live NTA feed.
     */
    public TripUpdateEvent publish(TripUpdateEvent event) {
        publishBatch(List.of(event));

        return event;
    }

    /**
     * Persists a bounded group before background Kafka delivery. The durable
     * outbox prevents a temporary broker failure from discarding NTA data.
     */
    public void publishBatch(List<TripUpdateEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        kafkaOutboxService.enqueueTripUpdates(events);
    }
}
