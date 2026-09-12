package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.dto.LiveVehiclePositionResponse;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.LiveVehiclePositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stores one latest position per vehicle in a Redis hash.
 *
 * <p>Hash fields are pruned on read based on {@code cachedAt}; that avoids a
 * network-wide Redis {@code KEYS} scan and prevents old vehicles from being
 * shown after their NTA positions stop arriving.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLiveVehiclePositionService
        implements LiveVehiclePositionService {

    private static final String VEHICLES_KEY = "live:vehicles";
    private static final String VEHICLE_OBSERVED_AT_KEY =
            "live:vehicles:observed-at";
    private static final String SOURCE = "NTA_VEHICLES_REALTIME";

    /**
     * Stores a position only when it is at least as recent as the cached one.
     * Keeping the comparison and both hash writes inside Redis prevents a late
     * Kafka message, overlapping poll, or second application instance from
     * moving a vehicle backwards on the live map.
     */
    private static final DefaultRedisScript<Long> PUT_IF_NOT_OLDER =
            new DefaultRedisScript<>(
                    """
                    local existingObservedAt = redis.call('HGET', KEYS[2], ARGV[1])
                    if not existingObservedAt or tonumber(ARGV[2]) >= tonumber(existingObservedAt) then
                        redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
                        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
                        return 1
                    end
                    return 0
                    """,
                    Long.class
            );

    /**
     * Removes a stale snapshot only if a newer writer has not replaced it.
     * The comparison avoids the read-then-delete race in {@link #findAll()}.
     */
    private static final DefaultRedisScript<Long> DELETE_IF_UNCHANGED =
            new DefaultRedisScript<>(
                    """
                    local currentValue = redis.call('HGET', KEYS[1], ARGV[1])
                    local currentObservedAt = redis.call('HGET', KEYS[2], ARGV[1])
                    if currentValue == ARGV[2]
                       and (ARGV[3] == '' or not currentObservedAt or currentObservedAt == ARGV[3]) then
                        redis.call('HDEL', KEYS[1], ARGV[1])
                        redis.call('HDEL', KEYS[2], ARGV[1])
                        return 1
                    end
                    return 0
                    """,
                    Long.class
            );

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.live-vehicle-state.ttl-seconds:900}")
    private long ttlSeconds;

    @Override
    public void update(VehiclePositionEvent event) {
        LiveVehiclePositionResponse state = new LiveVehiclePositionResponse(
                event.eventId(),
                event.externalVehicleId(),
                event.externalTripId(),
                event.externalRouteId(),
                event.tripStartTime(),
                event.tripStartDate(),
                event.directionId(),
                event.latitude(),
                event.longitude(),
                event.bearing(),
                event.observedAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                SOURCE
        );

        try {
            String serializedState = objectMapper.writeValueAsString(state);
            stringRedisTemplate.execute(
                    PUT_IF_NOT_OLDER,
                    List.of(VEHICLES_KEY, VEHICLE_OBSERVED_AT_KEY),
                    event.externalVehicleId(),
                    String.valueOf(event.observedAt().toEpochMilli()),
                    serializedState
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize live vehicle state",
                    exception
            );
        }
    }

    @Override
    public List<LiveVehiclePositionResponse> findAll() {

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(
                VEHICLES_KEY
        );
        Map<Object, Object> observedAtEntries =
                stringRedisTemplate.opsForHash().entries(VEHICLE_OBSERVED_AT_KEY);
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusSeconds(ttlSeconds);
        List<LiveVehiclePositionResponse> states = new ArrayList<>();
        List<ExpiredVehicle> expiredVehicles = new ArrayList<>();

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                // convert JSON string into LiveVehiclePositionResponse
                LiveVehiclePositionResponse state = objectMapper.readValue(
                        entry.getValue().toString(),
                        LiveVehiclePositionResponse.class
                );

                if (state.cachedAt().isBefore(cutoff)) {
                    expiredVehicles.add(new ExpiredVehicle(
                            entry.getKey().toString(),
                            entry.getValue().toString(),
                            asString(observedAtEntries.get(entry.getKey()))
                    ));
                } else {
                    states.add(state);
                }
            } catch (JsonProcessingException exception) {
                expiredVehicles.add(new ExpiredVehicle(
                        entry.getKey().toString(),
                        entry.getValue().toString(),
                        asString(observedAtEntries.get(entry.getKey()))
                ));
                log.warn("Removing unreadable cached vehicle state", exception);
            }
        }

        for (ExpiredVehicle expiredVehicle : expiredVehicles) {
            // Redis deletes this field only when it still matches the exact
            // snapshot examined above. A fresh concurrent update is retained.
            stringRedisTemplate.execute(
                    DELETE_IF_UNCHANGED,
                    List.of(VEHICLES_KEY, VEHICLE_OBSERVED_AT_KEY),
                    expiredVehicle.vehicleId(),
                    expiredVehicle.serializedState(),
                    expiredVehicle.observedAtEpochMillis() == null
                            ? ""
                            : expiredVehicle.observedAtEpochMillis()
            );
        }

        return states.stream()
                .sorted(Comparator
                        .comparing(LiveVehiclePositionResponse::externalRouteId,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(LiveVehiclePositionResponse::vehicleId))
                .toList();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private record ExpiredVehicle(
            String vehicleId,
            String serializedState,
            String observedAtEpochMillis
    ) {
    }
}
