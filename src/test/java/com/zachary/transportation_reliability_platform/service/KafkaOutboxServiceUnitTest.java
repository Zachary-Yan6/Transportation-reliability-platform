package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.config.KafkaOutboxProperties;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit coverage for durable Kafka hand-off, retry, and dead-letter behavior. */
@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void enqueuePersistsEventsAndRejectsAnOverloadedOutbox() throws Exception {
        TripUpdateEvent event = tripEvent();
        when(objectMapper.writeValueAsString(event)).thenReturn("{\"event\":true}");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(4L);

        service(1_000, 2).enqueueTripUpdates(List.of(event));

        verify(jdbcTemplate).execute(anyString());
        verify(jdbcTemplate).batchUpdate(
                anyString(),
                any(List.class),
                eq(1),
                any()
        );
        assertThat(meterRegistry.counter("transit.kafka.outbox.enqueued").count()).isEqualTo(1.0d);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1_000L);
        assertThatThrownBy(() -> service(1_000, 2).enqueueTripUpdates(List.of(event)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outbox is at capacity");
        assertThat(meterRegistry.counter("transit.kafka.outbox.backpressure").count())
                .isEqualTo(1.0d);
    }

    @Test
    void enqueueRejectsAnUnserializableEventBeforeWritingIt() throws Exception {
        TripUpdateEvent event = tripEvent();
        when(objectMapper.writeValueAsString(event)).thenThrow(jsonError());

        assertThatThrownBy(() -> service(10, 2).enqueueTripUpdates(List.of(event)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unable to persist");
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void emptyBatchesAvoidSerializationAndDatabaseWork() {
        KafkaOutboxService service = service(10, 2);

        service.enqueueTripUpdates(List.of());
        service.enqueueVehiclePositions(List.of());

        verifyNoInteractions(jdbcTemplate, objectMapper, kafkaTemplate);
    }

    @Test
    void enqueueAndDispatchSupportVehiclePositionEvents() throws Exception {
        VehiclePositionEvent event = vehicleEvent();
        when(objectMapper.writeValueAsString(event)).thenReturn("{\"vehicle\":true}");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        service(10, 2).enqueueVehiclePositions(List.of(event));

        verify(jdbcTemplate).batchUpdate(
                anyString(),
                any(List.class),
                eq(1),
                any()
        );

        stubDue(outboxRow(6L, event.eventId(), VehiclePositionEventProducer.TOPIC,
                event.externalVehicleId(), "vehicle-json", 0));
        when(objectMapper.readValue("vehicle-json", VehiclePositionEvent.class)).thenReturn(event);
        when(kafkaTemplate.send(
                VehiclePositionEventProducer.TOPIC,
                event.externalVehicleId(),
                event
        )).thenReturn(CompletableFuture.completedFuture(null));

        service(10, 2).dispatchDueEvents();

        verify(jdbcTemplate).update(contains("SET status = 'SENT'"), eq(6L));
    }

    @Test
    void dispatchMarksAcknowledgedEventsSentAndFailuresForRetry() throws Exception {
        TripUpdateEvent event = tripEvent();
        stubDue(outboxRow(7L, event.eventId(), TripUpdateEventProducer.TOPIC,
                "1:TRIP-1", "trip-json", 0));
        when(objectMapper.readValue("trip-json", TripUpdateEvent.class)).thenReturn(event);
        when(kafkaTemplate.send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", event))
                .thenReturn(CompletableFuture.completedFuture(null));

        service(10, 2).dispatchDueEvents();

        verify(jdbcTemplate).update(contains("SET status = 'SENT'"), eq(7L));
        assertThat(meterRegistry.counter("transit.kafka.outbox.sent").count()).isEqualTo(1.0d);

        stubDue(outboxRow(8L, event.eventId(), TripUpdateEventProducer.TOPIC,
                "1:TRIP-1", "trip-json", 0));
        when(kafkaTemplate.send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", event))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")));

        service(10, 2).dispatchDueEvents();

        verify(jdbcTemplate).update(
                contains("SET status = 'RETRY'"),
                eq(1),
                any(),
                anyString(),
                eq(8L)
        );
        assertThat(meterRegistry.counter("transit.kafka.outbox.retry").count()).isEqualTo(1.0d);
    }

    @Test
    void dispatchMarksFinalOrInvalidEventsAsDurableDeadLetters() throws Exception {
        TripUpdateEvent event = tripEvent();
        stubDue(outboxRow(9L, event.eventId(), TripUpdateEventProducer.TOPIC,
                "1:TRIP-1", "trip-json", 1));
        when(objectMapper.readValue("trip-json", TripUpdateEvent.class)).thenReturn(event);
        when(kafkaTemplate.send(TripUpdateEventProducer.TOPIC, "1:TRIP-1", event))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));

        service(10, 2).dispatchDueEvents();

        verify(jdbcTemplate).update(
                contains("SET status = 'DEAD'"),
                eq(2),
                anyString(),
                eq(9L)
        );
        assertThat(meterRegistry.counter("transit.kafka.outbox.dead").count()).isEqualTo(1.0d);

        stubDue(outboxRow(10L, UUID.randomUUID(), "unknown-topic", "key", "{}", 1));
        service(10, 2).dispatchDueEvents();
        verify(jdbcTemplate).update(
                contains("SET status = 'DEAD'"),
                eq(2),
                anyString(),
                eq(10L)
        );
    }

    @Test
    void emptyOutboxDoesNotContactKafka() throws Exception {
        stubDue();

        service(10, 2).dispatchDueEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private KafkaOutboxService service(int maxPending, int maxAttempts) {
        return new KafkaOutboxService(
                jdbcTemplate,
                objectMapper,
                kafkaTemplate,
                new KafkaOutboxProperties(maxPending, 10, maxAttempts, 2, 10, 1),
                meterRegistry
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubDue(OutboxRow... rows) throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return java.util.Arrays.stream(rows)
                            .map(row -> mapRow(mapper, row))
                            .toList();
                });
    }

    @SuppressWarnings("rawtypes")
    private Object mapRow(RowMapper mapper, OutboxRow row) {
        try {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("id")).thenReturn(row.id());
            when(resultSet.getObject("event_id", UUID.class)).thenReturn(row.eventId());
            when(resultSet.getString("topic")).thenReturn(row.topic());
            when(resultSet.getString("message_key")).thenReturn(row.messageKey());
            when(resultSet.getString("payload")).thenReturn(row.payload());
            when(resultSet.getInt("attempt_count")).thenReturn(row.attemptCount());
            return mapper.mapRow(resultSet, 0);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static OutboxRow outboxRow(
            long id,
            UUID eventId,
            String topic,
            String messageKey,
            String payload,
            int attemptCount
    ) {
        return new OutboxRow(id, eventId, topic, messageKey, payload, attemptCount);
    }

    private static TripUpdateEvent tripEvent() {
        return new TripUpdateEvent(
                UUID.randomUUID(), 1L, "TRIP-1", "STOP-1", 1, 30,
                Instant.parse("2026-09-19T12:00:00Z"), 12L, 13L
        );
    }

    private static VehiclePositionEvent vehicleEvent() {
        return new VehiclePositionEvent(
                UUID.randomUUID(), "VEHICLE-1", "TRIP-1", "ROUTE-1",
                "12:00:00", "20260919", (short) 0,
                53.3, -6.2, 90.0, Instant.parse("2026-09-19T12:00:00Z")
        );
    }

    private static JsonProcessingException jsonError() {
        return new JsonProcessingException("bad json") {
        };
    }

    private record OutboxRow(
            long id,
            UUID eventId,
            String topic,
            String messageKey,
            String payload,
            int attemptCount
    ) {
    }
}
