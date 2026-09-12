package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.PublishTripUpdateRequest;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * test api: A delay event is artificially introduced to verify the end-to-end pipeline: Kafka → PostgreSQL → Redis.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class TripUpdateEventController {

    private final TripUpdateEventProducer tripUpdateEventProducer;

    @PostMapping("/trip-updates")
    public ResponseEntity<TripUpdateEvent> publishTripUpdate(
            @Valid @RequestBody PublishTripUpdateRequest request
    ) {
        TripUpdateEvent event = tripUpdateEventProducer.publish(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(event);
    }
}