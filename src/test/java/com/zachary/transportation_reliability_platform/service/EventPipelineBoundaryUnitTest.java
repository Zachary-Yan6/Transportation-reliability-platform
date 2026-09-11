package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.PublishTripUpdateRequest;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.consumer.LiveTripStateConsumer;
import com.zachary.transportation_reliability_platform.service.consumer.LiveVehiclePositionConsumer;
import com.zachary.transportation_reliability_platform.service.consumer.TripUpdateEventConsumer;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import com.zachary.transportation_reliability_platform.websocket.LiveUpdateBroadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Kafka-facing components. Kafka itself is never started: a
 * completed future represents Kafka's acknowledgement at this boundary.
 */
class EventPipelineBoundaryUnitTest {

    @Test
    void liveProjectionConsumersForwardEventsToTheirDedicatedReadModels() {
        TripUpdateEvent tripEvent = resolvedTripEvent();
        VehiclePositionEvent vehicleEvent = vehicleEvent();
        LiveTripStateService tripStateService = mock(LiveTripStateService.class);
        LiveVehiclePositionService vehicleStateService = mock(LiveVehiclePositionService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);

        new LiveTripStateConsumer(tripStateService).consume(tripEvent);
        new LiveVehiclePositionConsumer(vehicleStateService, broadcaster)
                .consume(vehicleEvent);

        verify(tripStateService).update(tripEvent);
        verify(vehicleStateService).update(vehicleEvent);
        verify(broadcaster).signalChange("vehicle-positions");
    }

    @Test
    void resolvedTripEventIsPersistedInUtcAndSignalsTheDashboard() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        when(observationService.saveIfAbsent(any())).thenReturn(true);

        TripUpdateEvent event = resolvedTripEvent();
        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(event);

        ArgumentCaptor<TripStopDelayObservation> captor =
                ArgumentCaptor.forClass(TripStopDelayObservation.class);
        verify(observationService).saveIfAbsent(captor.capture());
        TripStopDelayObservation saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(event.eventId());
        assertThat(saved.getTripId()).isEqualTo(5L);
        assertThat(saved.getStopId()).isEqualTo(7L);
        assertThat(saved.getObservedAt()).isEqualTo(
                event.observedAt().atOffset(ZoneOffset.UTC)
        );
        verify(broadcaster).signalChange("trip-delays");
        verify(tripService, never()).getOne(any(), eq(false));
    }

    @Test
    void duplicateOrUnresolvableEventsDoNotNotifyTheDashboard() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        when(observationService.saveIfAbsent(any())).thenReturn(false);

        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(resolvedTripEvent());

        TripUpdateEvent fallbackEvent = new TripUpdateEvent(
                UUID.randomUUID(), 1L, "MISSING", "STOP-1", 2, 10,
                Instant.parse("2026-09-11T12:00:00Z")
        );
        when(tripService.getOne(any(), eq(false))).thenReturn(null);
        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(fallbackEvent);

        verify(observationService).saveIfAbsent(any());
        verify(broadcaster, never()).signalChange("trip-delays");
        verify(stopService, never()).getOne(any(), eq(false));
    }

    @Test
    void legacyEventUsesStaticGtfsFallbackOnlyWhenTheStopSequenceMatches() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        Trip trip = new Trip();
        trip.setId(12L);
        Stop stop = new Stop();
        stop.setId(13L);
        when(tripService.getOne(any(), eq(false))).thenReturn(trip);
        when(stopService.getOne(any(), eq(false))).thenReturn(stop);
        when(stopTimeService.getOne(any(), eq(false))).thenReturn(new StopTime());
        when(observationService.saveIfAbsent(any())).thenReturn(true);

        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(new TripUpdateEvent(
                UUID.randomUUID(), 1L, "TRIP-1", "STOP-1", 2, -15,
                Instant.parse("2026-09-11T12:00:00Z")
        ));

        ArgumentCaptor<TripStopDelayObservation> captor =
                ArgumentCaptor.forClass(TripStopDelayObservation.class);
        verify(observationService).saveIfAbsent(captor.capture());
        assertThat(captor.getValue().getTripId()).isEqualTo(12L);
        assertThat(captor.getValue().getStopId()).isEqualTo(13L);
        verify(broadcaster).signalChange("trip-delays");
    }

    @Test
    void producersAwaitSuccessfulKafkaAcknowledgementAndTranslateFailures() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, TripUpdateEvent> tripTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, VehiclePositionEvent> vehicleTemplate = mock(KafkaTemplate.class);
        TripUpdateEvent tripEvent = resolvedTripEvent();
        VehiclePositionEvent vehicleEvent = vehicleEvent();

        when(tripTemplate.send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", tripEvent))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(vehicleTemplate.send(
                VehiclePositionEventProducer.TOPIC, "VEHICLE-1", vehicleEvent
        )).thenReturn(CompletableFuture.completedFuture(null));

        new TripUpdateEventProducer(tripTemplate).publishBatch(List.of(tripEvent));
        new VehiclePositionEventProducer(vehicleTemplate).publishBatch(List.of(vehicleEvent));

        verify(tripTemplate).send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", tripEvent);
        verify(vehicleTemplate).send(
                VehiclePositionEventProducer.TOPIC, "VEHICLE-1", vehicleEvent
        );

        when(tripTemplate.send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", tripEvent))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThatThrownBy(() -> new TripUpdateEventProducer(tripTemplate)
                .publishBatch(List.of(tripEvent))).isInstanceOf(BusinessException.class);
    }

    @Test
    void manualTripProducerCreatesAnEventAndEmptyBatchesDoNotCallKafka() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, TripUpdateEvent> template = mock(KafkaTemplate.class);
        when(template.send(eq(TripUpdateEventProducer.TOPIC), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        TripUpdateEventProducer producer = new TripUpdateEventProducer(template);

        TripUpdateEvent event = producer.publish(new PublishTripUpdateRequest(
                3L, "TRIP-3", "STOP-3", 2, 60
        ));

        assertThat(event.feedVersionId()).isEqualTo(3L);
        assertThat(event.externalTripId()).isEqualTo("TRIP-3");
        assertThat(event.eventId()).isNotNull();
        producer.publishBatch(List.of());
        verify(template).send(eq(TripUpdateEventProducer.TOPIC), eq("3:TRIP-3"), eq(event));
    }

    private static TripUpdateEvent resolvedTripEvent() {
        return new TripUpdateEvent(
                UUID.randomUUID(), 1L, "TRIP-1", "STOP-1", 2, 60,
                Instant.parse("2026-09-11T12:00:00Z"), 5L, 7L
        );
    }

    private static VehiclePositionEvent vehicleEvent() {
        return new VehiclePositionEvent(
                UUID.randomUUID(), "VEHICLE-1", "TRIP-1", "ROUTE-1",
                null, null, null, 53.3, -6.2, null,
                Instant.parse("2026-09-11T12:00:00Z")
        );
    }
}
