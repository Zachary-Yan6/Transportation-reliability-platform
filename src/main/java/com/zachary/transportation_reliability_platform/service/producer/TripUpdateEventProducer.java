package com.zachary.transportation_reliability_platform.service.producer;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.PublishTripUpdateRequest;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TripUpdateEventProducer {

    public static final String TOPIC = "trip-update-events";

    private final KafkaTemplate<String, TripUpdateEvent> kafkaTemplate;

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
     * Publishes a bounded group of events concurrently, then waits until Kafka
     * confirms every event in that group. Calling send(...).get(...) for every
     * individual stop update made full-network ingestion unnecessarily slow.
     */
    public void publishBatch(List<TripUpdateEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        List<CompletableFuture<SendResult<String, TripUpdateEvent>>> sendFutures =
                events.stream()
                .map(event -> kafkaTemplate.send(
                        TOPIC,
                        messageKey(event),
                        event
                ))
                .toList();

        try {
            CompletableFuture.allOf(
                            sendFutures.toArray(CompletableFuture[]::new)
                    )
                    .get(30, TimeUnit.SECONDS);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Kafka publishing was interrupted"
            );

        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to publish trip update event: "
                            + exception.getMessage()
            );
        }
    }

    private String messageKey(TripUpdateEvent event) {
        return event.feedVersionId()
                + ":"
                + event.externalTripId();
    }
}
