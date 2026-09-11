package com.zachary.transportation_reliability_platform.service.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.StopService;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import com.zachary.transportation_reliability_platform.service.TripService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayObservationService;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import com.zachary.transportation_reliability_platform.websocket.LiveUpdateBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

/**
 * Listens for real-time Trip update events from Kafka
 * and saves them as database delay observations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripUpdateEventConsumer {

    // Finds a Trip using its external GTFS trip_id.
    private final TripService tripService;

    // Finds a Stop using its external GTFS stop_id.
    private final StopService stopService;

    // Confirms that the selected Trip actually visits the selected Stop.
    private final StopTimeService stopTimeService;

    // Saves the final delay observation into PostgreSQL.
    private final TripStopDelayObservationService delayObservationService;
    private final LiveUpdateBroadcaster liveUpdateBroadcaster;

    /**
     * topics: the Kafka Topic to listen to.
     * groupId: each message is handled once per consumer group.
     *
     * Kafka commits the message offset only after this method returns normally.
     */
    @KafkaListener(
            topics = TripUpdateEventProducer.TOPIC,
            groupId = "trip-delay-observation-consumer"
    )
    @Transactional
    public void consume(TripUpdateEvent event) {

        // Full-network polling may produce thousands of events. Keep per-event
        // diagnostics available without flooding normal application logs.
        log.debug("Received trip update event: {}", event);

        ResolvedIds resolvedIds = resolveInternalIds(event);

        if (resolvedIds == null) {
            // Invalid manual/legacy events are skipped so one bad message never
            // blocks the Kafka partition.
            return;
        }

        // Convert the Kafka event into a PostgreSQL entity.
        TripStopDelayObservation observation =
                new TripStopDelayObservation();

        // Store the original event ID for idempotency and duplicate prevention.
        observation.setEventId(event.eventId());

        // Save verified internal database IDs for efficient future queries.
        observation.setFeedVersionId(event.feedVersionId());
        observation.setTripId(resolvedIds.tripId());
        observation.setStopId(resolvedIds.stopId());

        // Store the stop sequence and delay amount.
        observation.setStopSequence(event.stopSequence());
        observation.setDelaySeconds(event.delaySeconds());

        // Convert Kafka Instant to OffsetDateTime in UTC.
        observation.setObservedAt(
                event.observedAt().atOffset(ZoneOffset.UTC)
        );

        // PostgreSQL handles duplicate event IDs in the same INSERT statement.
        // This is faster and safer than a separate "does this exist?" query.
        boolean inserted = delayObservationService.saveIfAbsent(observation);

        if (!inserted) {
            log.debug("Skipping duplicate trip update event: {}", event.eventId());
            return;
        }

        liveUpdateBroadcaster.signalChange("trip-delays");

        log.debug(
                "Saved delay observation. eventId={}, tripId={}, stopId={}, delaySeconds={}",
                event.eventId(),
                resolvedIds.tripId(),
                resolvedIds.stopId(),
                event.delaySeconds()
        );
    }

    /**
     * Normal NTA events already contain IDs resolved by the ingestion service.
     * The fallback preserves the manual testing endpoint and old Kafka records.
     */
    private ResolvedIds resolveInternalIds(TripUpdateEvent event) {
        if (event.resolvedTripId() != null
                && event.resolvedStopId() != null) {
            return new ResolvedIds(
                    event.resolvedTripId(),
                    event.resolvedStopId()
            );
        }

        Trip trip = tripService.getOne(
                Wrappers.<Trip>lambdaQuery()
                        .eq(Trip::getFeedVersionId, event.feedVersionId())
                        .eq(Trip::getExternalTripId, event.externalTripId()),
                false
        );

        if (trip == null) {
            log.warn(
                    "Skipping event {}: trip not found. feedVersionId={}, externalTripId={}",
                    event.eventId(),
                    event.feedVersionId(),
                    event.externalTripId()
            );
            return null;
        }

        Stop stop = stopService.getOne(
                Wrappers.<Stop>lambdaQuery()
                        .eq(Stop::getFeedVersionId, event.feedVersionId())
                        .eq(Stop::getExternalStopId, event.externalStopId()),
                false
        );

        if (stop == null) {
            log.warn(
                    "Skipping event {}: stop not found. feedVersionId={}, externalStopId={}",
                    event.eventId(),
                    event.feedVersionId(),
                    event.externalStopId()
            );
            return null;
        }

        StopTime stopTime = stopTimeService.getOne(
                Wrappers.<StopTime>lambdaQuery()
                        .eq(StopTime::getTripId, trip.getId())
                        .eq(StopTime::getStopId, stop.getId())
                        .eq(StopTime::getStopSequence, event.stopSequence()),
                false
        );

        if (stopTime == null) {
            log.warn(
                    "Skipping event {}: stop sequence does not match trip. tripId={}, stopId={}, sequence={}",
                    event.eventId(),
                    trip.getId(),
                    stop.getId(),
                    event.stopSequence()
            );
            return null;
        }

        return new ResolvedIds(trip.getId(), stop.getId());
    }

    private record ResolvedIds(Long tripId, Long stopId) {
    }
}
