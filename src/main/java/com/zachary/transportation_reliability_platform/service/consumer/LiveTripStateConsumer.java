package com.zachary.transportation_reliability_platform.service.consumer;

import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.LiveTripStateService;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A second, independent consumer group for the same Kafka event stream.
 *
 * <p>It updates Redis only. If Redis becomes unavailable, PostgreSQL history
 * collection continues through the separate delay-observation consumer.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveTripStateConsumer {

    private final LiveTripStateService liveTripStateService;

    @KafkaListener(
            topics = TripUpdateEventProducer.TOPIC,
            groupId = "live-trip-state-consumer"
    )
    public void consume(TripUpdateEvent event) {
        liveTripStateService.update(event);
        log.debug("Updated Redis live state for event {}", event.eventId());
    }
}
