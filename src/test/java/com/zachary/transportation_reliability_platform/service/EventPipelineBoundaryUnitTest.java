package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Kafka-facing components. Producers write to the durable
 * PostgreSQL outbox; consumers are tested independently from Kafka itself.
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
    void resolvedTripEventBatchIsPersistedInUtcAndSignalsTheDashboardOnce() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        when(observationService.saveBatchIfAbsent(anyList())).thenReturn(2);

        TripUpdateEvent event = resolvedTripEvent();
        TripUpdateEvent secondEvent = new TripUpdateEvent(
                UUID.randomUUID(), 1L, "TRIP-2", "STOP-2", 3, 90,
                Instant.parse("2026-09-11T12:01:00Z"), 8L, 9L
        );
        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(List.of(event, secondEvent));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(observationService).saveBatchIfAbsent(captor.capture());
        @SuppressWarnings("unchecked")
        List<TripStopDelayObservation> savedBatch = captor.getValue();
        assertThat(savedBatch).hasSize(2);
        TripStopDelayObservation saved = savedBatch.getFirst();
        assertThat(saved.getEventId()).isEqualTo(event.eventId());
        assertThat(saved.getTripId()).isEqualTo(5L);
        assertThat(saved.getStopId()).isEqualTo(7L);
        assertThat(saved.getObservedAt()).isEqualTo(
                event.observedAt().atOffset(ZoneOffset.UTC)
        );
        verify(broadcaster).signalChange("trip-delays");
        verify(tripService, never()).list(any(QueryWrapper.class));
    }

    @Test
    void duplicateOrUnresolvableEventsDoNotNotifyTheDashboard() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        when(observationService.saveBatchIfAbsent(anyList())).thenReturn(0);

        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(List.of(resolvedTripEvent()));

        TripUpdateEvent fallbackEvent = new TripUpdateEvent(
                UUID.randomUUID(), 1L, "MISSING", "STOP-1", 2, 10,
                Instant.parse("2026-09-11T12:00:00Z")
        );
        when(tripService.list(any(QueryWrapper.class))).thenReturn(List.of());
        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(List.of(fallbackEvent));

        verify(observationService, times(1)).saveBatchIfAbsent(anyList());
        verify(broadcaster, never()).signalChange("trip-delays");
        // Fallback resolves both reference sets in bounded queries before it
        // determines that this event has no matching trip.
        verify(stopService).list(any(QueryWrapper.class));
        verify(stopTimeService, never()).list(any(QueryWrapper.class));
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
        trip.setFeedVersionId(1L);
        trip.setExternalTripId("TRIP-1");
        Stop stop = new Stop();
        stop.setId(13L);
        stop.setFeedVersionId(1L);
        stop.setExternalStopId("STOP-1");
        StopTime stopTime = new StopTime();
        stopTime.setTripId(12L);
        stopTime.setStopId(13L);
        stopTime.setStopSequence(2);
        when(tripService.list(any(QueryWrapper.class))).thenReturn(List.of(trip));
        when(stopService.list(any(QueryWrapper.class))).thenReturn(List.of(stop));
        when(stopTimeService.list(any(QueryWrapper.class))).thenReturn(List.of(stopTime));
        when(observationService.saveBatchIfAbsent(anyList())).thenReturn(1);

        new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        ).consume(List.of(new TripUpdateEvent(
                UUID.randomUUID(), 1L, "TRIP-1", "STOP-1", 2, -15,
                Instant.parse("2026-09-11T12:00:00Z")
        )));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(observationService).saveBatchIfAbsent(captor.capture());
        @SuppressWarnings("unchecked")
        List<TripStopDelayObservation> savedBatch = captor.getValue();
        assertThat(savedBatch).hasSize(1);
        assertThat(savedBatch.get(0).getTripId()).isEqualTo(12L);
        assertThat(savedBatch.get(0).getStopId()).isEqualTo(13L);
        verify(broadcaster).signalChange("trip-delays");
    }

    @Test
    void emptyAndInvalidLegacyBatchesDoNotWriteOrNotify() {
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        TripStopDelayObservationService observationService =
                mock(TripStopDelayObservationService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        TripUpdateEventConsumer consumer = new TripUpdateEventConsumer(
                tripService, stopService, stopTimeService, observationService, broadcaster
        );

        consumer.consume(List.of());
        when(tripService.list(any(QueryWrapper.class))).thenReturn(List.of());
        consumer.consume(List.of(new TripUpdateEvent(
                UUID.randomUUID(), 1L, "MISSING", "STOP-1", 2, 10,
                Instant.parse("2026-09-11T12:00:00Z")
        )));

        verify(observationService, never()).saveBatchIfAbsent(anyList());
        verify(broadcaster, never()).signalChange("trip-delays");
    }

    @Test
    void producersQueueEventsInTheDurableOutbox() {
        KafkaOutboxService outboxService = mock(KafkaOutboxService.class);
        TripUpdateEvent tripEvent = resolvedTripEvent();
        VehiclePositionEvent vehicleEvent = vehicleEvent();

        new TripUpdateEventProducer(outboxService).publishBatch(List.of(tripEvent));
        new VehiclePositionEventProducer(outboxService).publishBatch(List.of(vehicleEvent));

        verify(outboxService).enqueueTripUpdates(List.of(tripEvent));
        verify(outboxService).enqueueVehiclePositions(List.of(vehicleEvent));
    }

    @Test
    void manualTripProducerCreatesAnEventAndEmptyBatchesDoNotTouchTheOutbox() {
        KafkaOutboxService outboxService = mock(KafkaOutboxService.class);
        TripUpdateEventProducer producer = new TripUpdateEventProducer(outboxService);

        TripUpdateEvent event = producer.publish(new PublishTripUpdateRequest(
                3L, "TRIP-3", "STOP-3", 2, 60
        ));

        assertThat(event.feedVersionId()).isEqualTo(3L);
        assertThat(event.externalTripId()).isEqualTo("TRIP-3");
        assertThat(event.eventId()).isNotNull();
        producer.publishBatch(List.of());
        verify(outboxService).enqueueTripUpdates(List.of(event));
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
