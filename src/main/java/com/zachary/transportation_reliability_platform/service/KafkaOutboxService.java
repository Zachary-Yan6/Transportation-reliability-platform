package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.config.KafkaOutboxProperties;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Durable hand-off between NTA polling and Kafka.
 *
 * <p>Events enter PostgreSQL before Kafka is contacted. A Kafka outage can
 * therefore delay a live update, but cannot discard an NTA observation that
 * was already accepted by this application. The dispatcher retries with a
 * bounded exponential delay and retains exhausted records as a durable,
 * operator-visible dead-letter state.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaOutboxService {

    private static final String CAPACITY_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(720154019)";
    private static final String PENDING_COUNT_SQL = """
            SELECT COUNT(*) FROM kafka_outbox
            WHERE status IN ('PENDING', 'RETRY')
            """;
    private static final String INSERT_SQL = """
            INSERT INTO kafka_outbox (
                event_id, topic, message_key, payload, status,
                attempt_count, next_attempt_at
            ) VALUES (?, ?, ?, CAST(? AS jsonb), 'PENDING', 0, CURRENT_TIMESTAMP)
            ON CONFLICT (topic, event_id) DO NOTHING
            """;
    private static final String FIND_DUE_SQL = """
            SELECT id, event_id, topic, message_key, payload, attempt_count
            FROM kafka_outbox
            WHERE status IN ('PENDING', 'RETRY')
              AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
    private static final String MARK_SENT_SQL = """
            UPDATE kafka_outbox
            SET status = 'SENT', sent_at = CURRENT_TIMESTAMP,
                last_error = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
    private static final String MARK_RETRY_SQL = """
            UPDATE kafka_outbox
            SET status = 'RETRY', attempt_count = ?, next_attempt_at = ?,
                last_error = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
    private static final String MARK_DEAD_SQL = """
            UPDATE kafka_outbox
            SET status = 'DEAD', attempt_count = ?, last_error = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaOutboxProperties properties;
    private final MeterRegistry meterRegistry;

    /** Queues trip updates atomically before a background dispatcher sends them. */
    @Transactional
    public void enqueueTripUpdates(List<TripUpdateEvent> events) {
        enqueue(events.stream()
                .map(event -> new NewOutboxEvent(
                        event.eventId(),
                        TripUpdateEventProducer.TOPIC,
                        event.feedVersionId() + ":" + event.externalTripId(),
                        event
                ))
                .toList());
    }

    /** Queues vehicle positions atomically before a background dispatcher sends them. */
    @Transactional
    public void enqueueVehiclePositions(List<VehiclePositionEvent> events) {
        enqueue(events.stream()
                .map(event -> new NewOutboxEvent(
                        event.eventId(),
                        VehiclePositionEventProducer.TOPIC,
                        event.externalVehicleId(),
                        event
                ))
                .toList());
    }

    private void enqueue(List<NewOutboxEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        List<SerializedOutboxEvent> serialized = serialize(events);
        // This transaction-scoped PostgreSQL advisory lock makes the capacity
        // check correct even when several application instances poll at once.
        jdbcTemplate.execute(CAPACITY_LOCK_SQL);
        Long pending = jdbcTemplate.queryForObject(PENDING_COUNT_SQL, Long.class);
        long pendingCount = pending == null ? 0L : pending;
        if (pendingCount + serialized.size() > properties.maxPendingEvents()) {
            meterRegistry.counter("transit.kafka.outbox.backpressure").increment();
            throw new BusinessException(
                    ErrorCode.EVENT_BACKPRESSURE,
                    "Kafka delivery is delayed and the durable outbox is at capacity"
            );
        }

        jdbcTemplate.batchUpdate(
                INSERT_SQL,
                serialized,
                serialized.size(),
                (statement, event) -> {
                    statement.setObject(1, event.eventId());
                    statement.setString(2, event.topic());
                    statement.setString(3, event.messageKey());
                    statement.setString(4, event.payload());
                }
        );
        meterRegistry.counter("transit.kafka.outbox.enqueued")
                .increment(serialized.size());
    }

    /**
     * Sends due records in one short database transaction. If the JVM stops
     * after Kafka acknowledges a record but before this transaction commits,
     * the record may be sent again; every downstream projection is idempotent
     * by event ID and observed timestamp.
     */
    @Scheduled(fixedDelayString = "${app.kafka-outbox.dispatch-interval-ms:5000}")
    @SchedulerLock(
            name = "kafka-outbox-dispatch",
            lockAtLeastFor = "PT0S",
            lockAtMostFor = "PT2M"
    )
    @Transactional
    public void dispatchDueEvents() {
        List<OutboxEvent> dueEvents = jdbcTemplate.query(
                FIND_DUE_SQL,
                (resultSet, rowNumber) -> new OutboxEvent(
                        resultSet.getLong("id"),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("topic"),
                        resultSet.getString("message_key"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempt_count")
                ),
                properties.dispatchBatchSize()
        );
        if (dueEvents.isEmpty()) {
            return;
        }

        List<PendingSend> sends = new ArrayList<>();
        for (OutboxEvent event : dueEvents) {
            try {
                sends.add(new PendingSend(
                        event,
                        kafkaTemplate.send(
                                event.topic(),
                                event.messageKey(),
                                deserialize(event)
                        )
                ));
            } catch (RuntimeException | JsonProcessingException exception) {
                recordFailure(event, exception);
            }
        }

        waitForSends(sends);
    }

    private List<SerializedOutboxEvent> serialize(List<NewOutboxEvent> events) {
        try {
            return events.stream()
                    .map(event -> serialize(event))
                    .toList();
        } catch (OutboxSerializationException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Unable to persist a Kafka outbox event"
            );
        }
    }

    private SerializedOutboxEvent serialize(NewOutboxEvent event) {
        try {
            return new SerializedOutboxEvent(
                    event.eventId(),
                    event.topic(),
                    event.messageKey(),
                    objectMapper.writeValueAsString(event.payload())
            );
        } catch (JsonProcessingException exception) {
            throw new OutboxSerializationException(exception);
        }
    }

    private Object deserialize(OutboxEvent event) throws JsonProcessingException {
        if (TripUpdateEventProducer.TOPIC.equals(event.topic())) {
            return objectMapper.readValue(event.payload(), TripUpdateEvent.class);
        }
        if (VehiclePositionEventProducer.TOPIC.equals(event.topic())) {
            return objectMapper.readValue(event.payload(), VehiclePositionEvent.class);
        }
        throw new IllegalArgumentException("Unsupported outbox topic: " + event.topic());
    }

    private void waitForSends(List<PendingSend> sends) {
        if (sends.isEmpty()) {
            return;
        }

        try {
            CompletableFuture.allOf(sends.stream()
                            .map(PendingSend::future)
                            .toArray(CompletableFuture[]::new))
                    .get(properties.sendTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
            log.warn("Kafka outbox batch was not fully acknowledged: {}",
                    exception.getClass().getSimpleName());
        }

        for (PendingSend send : sends) {
            if (isSuccessful(send.future())) {
                jdbcTemplate.update(MARK_SENT_SQL, send.event().id());
                meterRegistry.counter("transit.kafka.outbox.sent").increment();
            } else {
                recordFailure(send.event(), new IllegalStateException(
                        "Kafka did not acknowledge the outbox record within the delivery window"
                ));
            }
        }
    }

    private boolean isSuccessful(CompletableFuture<?> future) {
        if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
            return false;
        }
        try {
            future.join();
            return true;
        } catch (CancellationException | CompletionException exception) {
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, Exception exception) {
        int attempts = event.attemptCount() + 1;
        String error = errorSummary(exception);
        if (attempts >= properties.maxAttempts()) {
            jdbcTemplate.update(MARK_DEAD_SQL, attempts, error, event.id());
            meterRegistry.counter("transit.kafka.outbox.dead").increment();
            log.error("Kafka outbox event moved to durable dead-letter state. id={}, topic={}",
                    event.id(), event.topic());
            return;
        }

        jdbcTemplate.update(
                MARK_RETRY_SQL,
                attempts,
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(retryDelaySeconds(attempts)),
                error,
                event.id()
        );
        meterRegistry.counter("transit.kafka.outbox.retry").increment();
        log.warn("Kafka outbox delivery failed; retrying later. id={}, attempt={}, topic={}",
                event.id(), attempts, event.topic());
    }

    private long retryDelaySeconds(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 30);
        long candidate;
        try {
            candidate = Math.multiplyExact(properties.retryBaseSeconds(), multiplier);
        } catch (ArithmeticException exception) {
            candidate = properties.retryMaxSeconds();
        }
        return Math.min(candidate, properties.retryMaxSeconds());
    }

    private String errorSummary(Exception exception) {
        String message = exception.getMessage();
        String summary = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return summary.substring(0, Math.min(summary.length(), 2_000));
    }

    private record NewOutboxEvent(
            UUID eventId,
            String topic,
            String messageKey,
            Object payload
    ) {
    }

    private record SerializedOutboxEvent(
            UUID eventId,
            String topic,
            String messageKey,
            String payload
    ) {
    }

    private record OutboxEvent(
            long id,
            UUID eventId,
            String topic,
            String messageKey,
            String payload,
            int attemptCount
    ) {
    }

    private record PendingSend(OutboxEvent event, CompletableFuture<?> future) {
    }

    private static final class OutboxSerializationException extends RuntimeException {
        private OutboxSerializationException(Throwable cause) {
            super(cause);
        }
    }
}
