package com.zachary.transportation_reliability_platform.service.producer;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes normalized vehicle GPS observations to their own Kafka stream.
 */
@Service
@RequiredArgsConstructor
public class VehiclePositionEventProducer {

    public static final String TOPIC = "vehicle-position-events";

    private final KafkaTemplate<String, VehiclePositionEvent> kafkaTemplate;

    public void publishBatch(List<VehiclePositionEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        List<CompletableFuture<SendResult<String, VehiclePositionEvent>>> sends =
                events.stream()
                        .map(event -> kafkaTemplate.send(
                                TOPIC,
                                event.externalVehicleId(),
                                event
                        ))
                        .toList();

        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Kafka vehicle-position publishing was interrupted"
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to publish vehicle-position event: "
                            + exception.getMessage()
            );
        }
    }
}
