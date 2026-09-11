package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.RealtimeStopTimeMatchRow;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.mapper.StopTimeMapper;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts the NTA GTFS-Realtime snapshot into validated Kafka events.
 *
 * <p>The service deliberately works in two phases. It first extracts candidate
 * stop updates from the full NTA response, then resolves all candidates against
 * the imported GTFS feed with a small number of joined queries. This avoids the
 * previous N+1 lookup pattern when all routes are being collected.</p>
 */
@Service
@RequiredArgsConstructor
public class NtaRealtimeIngestionService {

    private static final int DEFAULT_PUBLICATION_BATCH_SIZE = 250;
    private static final int MATCH_TRIP_ID_BATCH_SIZE = 500;

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final TripUpdateEventProducer tripUpdateEventProducer;
    private final StopTimeMapper stopTimeMapper;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a controlled number of valid updates. This overload is used by
     * the manual testing endpoint and keeps its existing API contract.
     */
    public NtaRealtimeIngestionResponse publishValidUpdates(
            Long feedVersionId,
            int maxEventsPerRun
    ) {
        return publishValidUpdates(feedVersionId, maxEventsPerRun, null);
    }

    /**
     * Publishes a controlled number of valid updates, optionally limited to a
     * single external route. A blank route value means all imported routes.
     */
    public NtaRealtimeIngestionResponse publishValidUpdates(
            Long feedVersionId,
            int maxEventsPerRun,
            String targetExternalRouteId
    ) {
        return publishValidUpdates(
                feedVersionId,
                maxEventsPerRun,
                targetExternalRouteId,
                DEFAULT_PUBLICATION_BATCH_SIZE
        );
    }

    /**
     * Reads one NTA snapshot and publishes every valid stop update when
     * {@code maxEventsPerRun} is zero. A positive value is a deliberate,
     * visible limit for controlled manual tests or staged rollouts.
     */
    public NtaRealtimeIngestionResponse publishValidUpdates(
            Long feedVersionId,
            int maxEventsPerRun,
            String targetExternalRouteId,
            int publicationBatchSize
    ) {
        validateLimits(maxEventsPerRun, publicationBatchSize);

        String rawFeed = ntaGtfsRealtimeClient.fetchRawFeed();

        try {
            JsonNode root = objectMapper.readTree(rawFeed);
            JsonNode entities = root.path("entity");
            Instant feedTimestamp = getFeedTimestamp(root);

            ExtractionResult extractionResult = extractCandidates(
                    entities,
                    targetExternalRouteId
            );

            Map<ExternalStopTimeKey, RealtimeStopTimeMatchRow> matches =
                    findStaticGtfsMatches(
                            feedVersionId,
                            extractionResult.candidates()
                    );

            List<TripUpdateEvent> events = new ArrayList<>();
            int skippedUpdateCount = extractionResult.skippedUpdateCount();

            for (RealtimeStopUpdateCandidate candidate
                    : extractionResult.candidates()) {
                RealtimeStopTimeMatchRow match = matches.get(
                        candidate.matchKey()
                );

                if (match == null) {
                    // The live update cannot be tied safely to our static
                    // schedule, so it must not enter the analytics pipeline.
                    skippedUpdateCount++;
                    continue;
                }

                if (isLimitReached(events.size(), maxEventsPerRun)) {
                    // The scheduled configuration uses 0 and does not
                    // truncate. Positive limits are for controlled runs.
                    break;
                }

                events.add(new TripUpdateEvent(
                        createDeterministicEventId(
                                feedVersionId,
                                candidate.externalTripId(),
                                candidate.externalStopId(),
                                candidate.stopSequence(),
                                feedTimestamp
                        ),
                        feedVersionId,
                        candidate.externalTripId(),
                        candidate.externalStopId(),
                        candidate.stopSequence(),
                        candidate.delaySeconds(),
                        feedTimestamp,
                        match.getTripId(),
                        match.getStopId()
                ));
            }

            publishInBatches(events, publicationBatchSize);

            return new NtaRealtimeIngestionResponse(
                    feedTimestamp,
                    extractionResult.scannedStopTimeUpdates(),
                    events.size(),
                    skippedUpdateCount,
                    List.copyOf(events)
            );

        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA API returned invalid JSON"
            );
        }
    }

    /**
     * Extracts syntactically complete delay updates before touching PostgreSQL.
     */
    private ExtractionResult extractCandidates(
            JsonNode entities,
            String targetExternalRouteId
    ) {
        List<RealtimeStopUpdateCandidate> candidates = new ArrayList<>();
        int scannedStopTimeUpdates = 0;
        int skippedUpdateCount = 0;

        for (JsonNode entity : entities) {
            JsonNode tripUpdate = entity.get("trip_update");

            if (tripUpdate == null) {
                continue;
            }

            JsonNode tripNode = tripUpdate.path("trip");
            String externalTripId = getText(tripNode, "trip_id");
            String externalRouteId = getText(tripNode, "route_id");

            if (!isTargetRoute(externalRouteId, targetExternalRouteId)) {
                continue;
            }

            for (JsonNode stopTimeUpdate : tripUpdate.path("stop_time_update")) {
                scannedStopTimeUpdates++;

                String externalStopId = getText(stopTimeUpdate, "stop_id");
                Integer stopSequence = getInteger(
                        stopTimeUpdate,
                        "stop_sequence"
                );
                Integer delaySeconds = getNestedDelay(
                        stopTimeUpdate,
                        "departure"
                );

                if (delaySeconds == null) {
                    delaySeconds = getNestedDelay(stopTimeUpdate, "arrival");
                }

                if (externalTripId == null
                        || externalStopId == null
                        || stopSequence == null
                        || delaySeconds == null) {
                    skippedUpdateCount++;
                    continue;
                }

                candidates.add(new RealtimeStopUpdateCandidate(
                        externalTripId,
                        externalStopId,
                        stopSequence,
                        delaySeconds
                ));
            }
        }

        return new ExtractionResult(
                scannedStopTimeUpdates,
                skippedUpdateCount,
                candidates
        );
    }

    /**
     * Resolves all candidates with joined queries in batches of trip IDs,
     * rather than issuing three SQL queries per live stop update.
     */
    private Map<ExternalStopTimeKey, RealtimeStopTimeMatchRow>
    findStaticGtfsMatches(
            Long feedVersionId,
            List<RealtimeStopUpdateCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return Map.of();
        }

        Set<String> externalTripIds = new LinkedHashSet<>();
        Set<String> externalStopIds = new LinkedHashSet<>();

        for (RealtimeStopUpdateCandidate candidate : candidates) {
            externalTripIds.add(candidate.externalTripId());
            externalStopIds.add(candidate.externalStopId());
        }

        List<String> stopIds = List.copyOf(externalStopIds);
        List<String> tripIds = List.copyOf(externalTripIds);
        Map<ExternalStopTimeKey, RealtimeStopTimeMatchRow> matches =
                new HashMap<>();

        for (int fromIndex = 0;
             fromIndex < tripIds.size();
             fromIndex += MATCH_TRIP_ID_BATCH_SIZE) {
            int toIndex = Math.min(
                    fromIndex + MATCH_TRIP_ID_BATCH_SIZE,
                    tripIds.size()
            );

            List<RealtimeStopTimeMatchRow> rows =
                    stopTimeMapper.findRealtimeMatches(
                            feedVersionId,
                            tripIds.subList(fromIndex, toIndex),
                            stopIds
                    );

            for (RealtimeStopTimeMatchRow row : rows) {
                matches.put(new ExternalStopTimeKey(
                        row.getExternalTripId(),
                        row.getExternalStopId(),
                        row.getStopSequence()
                ), row);
            }
        }

        return matches;
    }

    /**
     * Sends a bounded number of asynchronous Kafka records at a time. This
     * limits memory use while still avoiding a network round-trip per record.
     */
    private void publishInBatches(
            List<TripUpdateEvent> events,
            int publicationBatchSize
    ) {
        for (int fromIndex = 0;
             fromIndex < events.size();
             fromIndex += publicationBatchSize) {
            int toIndex = Math.min(
                    fromIndex + publicationBatchSize,
                    events.size()
            );

            tripUpdateEventProducer.publishBatch(
                    events.subList(fromIndex, toIndex)
            );
        }
    }

    /**
     * Returns true when the incoming update belongs to the configured route.
     * An empty target means no route filter is applied.
     */
    private boolean isTargetRoute(
            String externalRouteId,
            String targetExternalRouteId
    ) {
        if (targetExternalRouteId == null
                || targetExternalRouteId.isBlank()) {
            return true;
        }

        return targetExternalRouteId.trim().equals(externalRouteId);
    }

    private boolean isLimitReached(int eventCount, int maxEventsPerRun) {
        return maxEventsPerRun > 0 && eventCount >= maxEventsPerRun;
    }

    private void validateLimits(
            int maxEventsPerRun,
            int publicationBatchSize
    ) {
        if (maxEventsPerRun < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "maxEventsPerRun must be zero or a positive integer"
            );
        }

        if (publicationBatchSize < 1 || publicationBatchSize > 1_000) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "publicationBatchSize must be between 1 and 1000"
            );
        }
    }

    private Instant getFeedTimestamp(JsonNode root) {
        long timestampSeconds = root.path("header")
                .path("timestamp")
                .asLong(0);

        return timestampSeconds == 0
                ? Instant.now()
                : Instant.ofEpochSecond(timestampSeconds);
    }

    private String getText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull()
                ? null
                : value.asText();
    }

    private Integer getInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull()
                ? null
                : value.asInt();
    }

    private Integer getNestedDelay(JsonNode node, String timeType) {
        JsonNode delay = node.path(timeType).get("delay");

        return delay == null || delay.isNull()
                ? null
                : delay.asInt();
    }

    /**
     * Creates the same UUID when NTA sends the same trip-stop update again.
     */
    private UUID createDeterministicEventId(
            Long feedVersionId,
            String externalTripId,
            String externalStopId,
            Integer stopSequence,
            Instant feedTimestamp
    ) {
        String source = feedVersionId
                + "|"
                + externalTripId
                + "|"
                + externalStopId
                + "|"
                + stopSequence
                + "|"
                + feedTimestamp.toEpochMilli();

        return UUID.nameUUIDFromBytes(
                source.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record ExtractionResult(
            int scannedStopTimeUpdates,
            int skippedUpdateCount,
            List<RealtimeStopUpdateCandidate> candidates
    ) {
    }

    private record RealtimeStopUpdateCandidate(
            String externalTripId,
            String externalStopId,
            Integer stopSequence,
            Integer delaySeconds
    ) {
        private ExternalStopTimeKey matchKey() {
            return new ExternalStopTimeKey(
                    externalTripId,
                    externalStopId,
                    stopSequence
            );
        }
    }

    private record ExternalStopTimeKey(
            String externalTripId,
            String externalStopId,
            Integer stopSequence
    ) {
    }
}
