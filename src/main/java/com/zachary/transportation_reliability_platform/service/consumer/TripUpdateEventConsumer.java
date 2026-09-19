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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            groupId = "trip-delay-observation-consumer",
            containerFactory = "tripUpdateBatchKafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(List<TripUpdateEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        List<ResolvedEvent> resolvedEvents = resolveInternalIds(events);
        if (resolvedEvents.isEmpty()) {
            return;
        }

        List<TripStopDelayObservation> observations = resolvedEvents.stream()
                .map(this::toObservation)
                .toList();
        // PostgreSQL evaluates all event IDs in one INSERT ... ON CONFLICT
        // statement. This keeps at-least-once Kafka delivery idempotent without
        // a separate existence query for every record.
        int insertedCount = delayObservationService.saveBatchIfAbsent(observations);

        if (insertedCount > 0) {
            // A batch changes one read model, so notify the frontend once rather
            // than creating a WebSocket notification for every stop update.
            liveUpdateBroadcaster.signalChange("trip-delays");
        }

        log.debug(
                "Processed trip update batch. received={}, valid={}, inserted={}",
                events.size(),
                observations.size(),
                insertedCount
        );
    }

    /**
     * Normal NTA events already contain IDs resolved by the ingestion service.
     * The fallback preserves manual testing and old Kafka records, but resolves
     * all of their trip, stop, and stop-time references with bounded queries
     * rather than issuing three database lookups per event.
     */
    private List<ResolvedEvent> resolveInternalIds(List<TripUpdateEvent> events) {
        List<ResolvedEvent> resolvedEvents = new ArrayList<>();
        List<TripUpdateEvent> fallbackEvents = new ArrayList<>();

        for (TripUpdateEvent event : events) {
            if (event.resolvedTripId() != null && event.resolvedStopId() != null) {
                resolvedEvents.add(new ResolvedEvent(
                        event, new ResolvedIds(event.resolvedTripId(), event.resolvedStopId())
                ));
            } else {
                fallbackEvents.add(event);
            }
        }

        if (fallbackEvents.isEmpty()) {
            return resolvedEvents;
        }

        Set<Long> feedVersionIds = new HashSet<>();
        Set<String> externalTripIds = new HashSet<>();
        Set<String> externalStopIds = new HashSet<>();
        for (TripUpdateEvent event : fallbackEvents) {
            feedVersionIds.add(event.feedVersionId());
            externalTripIds.add(event.externalTripId());
            externalStopIds.add(event.externalStopId());
        }

        Map<FeedExternalKey, Long> tripIds = new HashMap<>();
        for (Trip trip : tripService.list(
                Wrappers.<Trip>query()
                        .in("feed_version_id", feedVersionIds)
                        .in("external_trip_id", externalTripIds)
        )) {
            tripIds.put(new FeedExternalKey(trip.getFeedVersionId(), trip.getExternalTripId()),
                    trip.getId());
        }
        Map<FeedExternalKey, Long> stopIds = new HashMap<>();
        for (Stop stop : stopService.list(
                Wrappers.<Stop>query()
                        .in("feed_version_id", feedVersionIds)
                        .in("external_stop_id", externalStopIds)
        )) {
            stopIds.put(new FeedExternalKey(stop.getFeedVersionId(), stop.getExternalStopId()),
                    stop.getId());
        }

        List<FallbackCandidate> candidates = new ArrayList<>();
        for (TripUpdateEvent event : fallbackEvents) {
            Long tripId = tripIds.get(new FeedExternalKey(
                    event.feedVersionId(), event.externalTripId()
            ));
            if (tripId == null) {
                log.warn("Skipping event {}: trip not found. feedVersionId={}, externalTripId={}",
                        event.eventId(), event.feedVersionId(), event.externalTripId());
                continue;
            }
            Long stopId = stopIds.get(new FeedExternalKey(
                    event.feedVersionId(), event.externalStopId()
            ));
            if (stopId == null) {
                log.warn("Skipping event {}: stop not found. feedVersionId={}, externalStopId={}",
                        event.eventId(), event.feedVersionId(), event.externalStopId());
                continue;
            }
            candidates.add(new FallbackCandidate(event, tripId, stopId));
        }

        if (candidates.isEmpty()) {
            return resolvedEvents;
        }

        Set<Long> candidateTripIds = candidates.stream()
                .map(FallbackCandidate::tripId)
                .collect(java.util.stream.Collectors.toSet());
        Set<TripStopSequenceKey> validStopTimes = stopTimeService.list(
                Wrappers.<StopTime>query().in("trip_id", candidateTripIds)
        ).stream().map(stopTime -> new TripStopSequenceKey(
                stopTime.getTripId(), stopTime.getStopId(), stopTime.getStopSequence()
        )).collect(java.util.stream.Collectors.toSet());

        for (FallbackCandidate candidate : candidates) {
            TripStopSequenceKey key = new TripStopSequenceKey(
                    candidate.tripId(), candidate.stopId(), candidate.event().stopSequence()
            );
            if (!validStopTimes.contains(key)) {
                log.warn("Skipping event {}: stop sequence does not match trip. tripId={}, stopId={}, sequence={}",
                        candidate.event().eventId(), candidate.tripId(), candidate.stopId(),
                        candidate.event().stopSequence());
                continue;
            }
            resolvedEvents.add(new ResolvedEvent(
                    candidate.event(), new ResolvedIds(candidate.tripId(), candidate.stopId())
            ));
        }

        return resolvedEvents;
    }

    private TripStopDelayObservation toObservation(ResolvedEvent resolvedEvent) {
        TripUpdateEvent event = resolvedEvent.event();
        TripStopDelayObservation observation = new TripStopDelayObservation();
        observation.setEventId(event.eventId());
        observation.setFeedVersionId(event.feedVersionId());
        observation.setTripId(resolvedEvent.resolvedIds().tripId());
        observation.setStopId(resolvedEvent.resolvedIds().stopId());
        observation.setStopSequence(event.stopSequence());
        observation.setDelaySeconds(event.delaySeconds());
        observation.setObservedAt(event.observedAt().atOffset(ZoneOffset.UTC));
        return observation;
    }

    private record ResolvedIds(Long tripId, Long stopId) {}

    private record ResolvedEvent(TripUpdateEvent event, ResolvedIds resolvedIds) {}

    private record FallbackCandidate(TripUpdateEvent event, Long tripId, Long stopId) {}

    private record FeedExternalKey(Long feedVersionId, String externalId) {}

    private record TripStopSequenceKey(Long tripId, Long stopId, Integer stopSequence) {}
}
