package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.LiveTripStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis implementation of the live-state projection.
 *
 * <p>One Redis hash represents one trip. Each hash field identifies a stop and
 * sequence, so a newer NTA update replaces that stop's prior live state. The
 * entire trip hash expires after the configured freshness window.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLiveTripStateService implements LiveTripStateService {

    private static final String KEY_PREFIX = "live:trip-delay:";
    private static final String SOURCE = "NTA_REALTIME";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.live-state.ttl-seconds:900}")
    private long ttlSeconds;

    @Override
    public void update(TripUpdateEvent event) {
        LiveTripStopDelayResponse state = new LiveTripStopDelayResponse(
                event.eventId(),
                event.feedVersionId(),
                event.externalTripId(),
                event.externalStopId(),
                event.stopSequence(),
                event.delaySeconds(),
                event.observedAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                SOURCE
        );

        try {
            String key = tripKey(event.feedVersionId(), event.externalTripId());

            stringRedisTemplate.opsForHash().put(
                    key,
                    stopField(event.externalStopId(), event.stopSequence()),
                    objectMapper.writeValueAsString(state)
            );
            stringRedisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException exception) {
            // This is a coding/data-shape problem. Throwing lets the independent
            // live-state consumer retry without affecting PostgreSQL persistence.
            throw new IllegalStateException(
                    "Unable to serialize live trip state",
                    exception
            );
        }
    }

    @Override
    public List<LiveTripStopDelayResponse> findByTrip(
            Long feedVersionId,
            String externalTripId
    ) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(
                tripKey(feedVersionId, externalTripId)
        );

        List<LiveTripStopDelayResponse> states = new ArrayList<>();

        for (Object value : entries.values()) {
            try {
                states.add(objectMapper.readValue(
                        value.toString(),
                        LiveTripStopDelayResponse.class
                ));
            } catch (JsonProcessingException exception) {
                // A corrupt cache value should not make the live API unusable.
                log.warn("Ignoring unreadable cached trip state", exception);
            }
        }

        return states.stream()
                .sorted(Comparator.comparing(
                        LiveTripStopDelayResponse::stopSequence
                ))
                .toList();
    }

    private String tripKey(Long feedVersionId, String externalTripId) {
        return KEY_PREFIX + feedVersionId + ":" + externalTripId;
    }

    private String stopField(String externalStopId, Integer stopSequence) {
        return externalStopId + ":" + stopSequence;
    }
}
