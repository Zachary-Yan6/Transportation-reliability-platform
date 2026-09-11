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
    private static final String SOURCE = "NTA_VEHICLES_REALTIME";

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
            stringRedisTemplate.opsForHash().put(
                    VEHICLES_KEY,
                    event.externalVehicleId(),
                    objectMapper.writeValueAsString(state)
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
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusSeconds(ttlSeconds);
        List<LiveVehiclePositionResponse> states = new ArrayList<>();
        List<Object> expiredVehicleIds = new ArrayList<>();

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                LiveVehiclePositionResponse state = objectMapper.readValue(
                        entry.getValue().toString(),
                        LiveVehiclePositionResponse.class
                );

                if (state.cachedAt().isBefore(cutoff)) {
                    expiredVehicleIds.add(entry.getKey());
                } else {
                    states.add(state);
                }
            } catch (JsonProcessingException exception) {
                expiredVehicleIds.add(entry.getKey());
                log.warn("Removing unreadable cached vehicle state", exception);
            }
        }

        if (!expiredVehicleIds.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(
                    VEHICLES_KEY,
                    expiredVehicleIds.toArray()
            );
        }

        return states.stream()
                .sorted(Comparator
                        .comparing(LiveVehiclePositionResponse::externalRouteId,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(LiveVehiclePositionResponse::vehicleId))
                .toList();
    }
}
