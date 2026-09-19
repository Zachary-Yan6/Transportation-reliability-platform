package com.zachary.transportation_reliability_platform.service.producer;

import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.KafkaOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Publishes normalized vehicle GPS observations to their own Kafka stream.
 */
@Service
@RequiredArgsConstructor
public class VehiclePositionEventProducer {

    public static final String TOPIC = "vehicle-position-events";

    private final KafkaOutboxService kafkaOutboxService;

    public void publishBatch(List<VehiclePositionEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        kafkaOutboxService.enqueueVehiclePositions(events);
    }
}
