package com.zachary.transportation_reliability_platform.service.consumer;

import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.LiveVehiclePositionService;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import com.zachary.transportation_reliability_platform.websocket.LiveUpdateBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Projects vehicle-position events into the Redis state read by the map.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveVehiclePositionConsumer {

    private final LiveVehiclePositionService liveVehiclePositionService;
    private final LiveUpdateBroadcaster liveUpdateBroadcaster;

    @KafkaListener(
            topics = VehiclePositionEventProducer.TOPIC,
            groupId = "live-vehicle-position-consumer"
    )
    public void consume(VehiclePositionEvent event) {
        liveVehiclePositionService.update(event);
        liveUpdateBroadcaster.signalChange("vehicle-positions");
        log.debug("Updated Redis vehicle state for {}", event.externalVehicleId());
    }
}
