package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.dto.LiveVehiclePositionResponse;
import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;
import com.zachary.transportation_reliability_platform.dto.RouteReliabilityResponse;
import com.zachary.transportation_reliability_platform.dto.TripOperationStatusResponse;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendar;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendarDate;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.impl.RedisLiveTripStateService;
import com.zachary.transportation_reliability_platform.service.impl.RedisLiveVehiclePositionService;
import com.zachary.transportation_reliability_platform.service.impl.RouteAnomalyDetectionServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.TripOperationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for in-memory projections and calendar-based business decisions.
 * No Redis, Kafka, HTTP service or database is started in these tests.
 */
@ExtendWith(MockitoExtension.class)
class LiveStateAndOperationsServiceUnitTest {

    @Mock
    private RouteReliabilityService routeReliabilityService;
    @Mock
    private TripService tripService;
    @Mock
    private ServiceCalendarService serviceCalendarService;
    @Mock
    private ServiceCalendarDateService serviceCalendarDateService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Test
    void anomalyDetectionClampsWindowAndClassifiesInsufficientHighMediumAndNone() {
        RouteAnomalyDetectionServiceImpl service =
                new RouteAnomalyDetectionServiceImpl(routeReliabilityService);

        when(routeReliabilityService.getRouteReliability(1L, 1))
                .thenReturn(reliability(1L, 9L, null, null));
        RouteAnomalyResponse insufficient = service.checkRoute(1L, 0);
        assertThat(insufficient.windowHours()).isEqualTo(1);
        assertThat(insufficient.severity()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(insufficient.anomalyDetected()).isFalse();

        when(routeReliabilityService.getRouteReliability(2L, 168))
                .thenReturn(reliability(2L, 10L, BigDecimal.valueOf(900), BigDecimal.ZERO));
        RouteAnomalyResponse high = service.checkRoute(2L, 999);
        assertThat(high.windowHours()).isEqualTo(168);
        assertThat(high.severity()).isEqualTo("HIGH");
        assertThat(high.anomalyDetected()).isTrue();

        when(routeReliabilityService.getRouteReliability(3L, 24))
                .thenReturn(reliability(3L, 10L, BigDecimal.valueOf(300), BigDecimal.valueOf(20)));
        assertThat(service.checkRoute(3L, 24).severity()).isEqualTo("MEDIUM");

        when(routeReliabilityService.getRouteReliability(4L, 24))
                .thenReturn(reliability(4L, 10L, BigDecimal.valueOf(299), BigDecimal.valueOf(1799)));
        assertThat(service.checkRoute(4L, 24).severity()).isEqualTo("NONE");
    }

    @Test
    void anomalyDetectionTreatsP90ThresholdAsHighEvenWhenAverageIsMissing() {
        when(routeReliabilityService.getRouteReliability(1L, 24))
                .thenReturn(reliability(1L, 10L, null, BigDecimal.valueOf(1800)));

        RouteAnomalyResponse result = new RouteAnomalyDetectionServiceImpl(
                routeReliabilityService
        ).checkRoute(1L, 24);

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.anomalyDetected()).isTrue();
    }

    @Test
    void tripOperationPrioritizesAddedAndRemovedCalendarDateExceptions() {
        TripOperationServiceImpl service = tripOperationService();
        LocalDate date = LocalDate.of(2026, 9, 14);
        when(tripService.getRequiredById(1L)).thenReturn(trip());

        ServiceCalendarDate added = new ServiceCalendarDate();
        added.setExceptionType((short) 1);
        when(serviceCalendarDateService.getOne(any(), eq(false))).thenReturn(added);
        TripOperationStatusResponse addedResult = service.getOperationStatus(1L, date);
        assertThat(addedResult.running()).isTrue();
        assertThat(addedResult.reason()).isEqualTo("ADDED_BY_CALENDAR_DATE");
        verify(serviceCalendarService, never()).getOne(any(), eq(false));

        ServiceCalendarDate removed = new ServiceCalendarDate();
        removed.setExceptionType((short) 2);
        when(serviceCalendarDateService.getOne(any(), eq(false))).thenReturn(removed);
        TripOperationStatusResponse removedResult = service.getOperationStatus(1L, date);
        assertThat(removedResult.running()).isFalse();
        assertThat(removedResult.reason()).isEqualTo("REMOVED_BY_CALENDAR_DATE");
    }

    @Test
    void tripOperationHandlesMissingCalendarDateRangeAndWeekdayRules() {
        TripOperationServiceImpl service = tripOperationService();
        when(tripService.getRequiredById(1L)).thenReturn(trip());
        when(serviceCalendarDateService.getOne(any(), eq(false))).thenReturn(null);

        when(serviceCalendarService.getOne(any(), eq(false))).thenReturn(null);
        assertThat(service.getOperationStatus(1L, LocalDate.of(2026, 9, 14)).reason())
                .isEqualTo("NO_CALENDAR_RULE");

        ServiceCalendar calendar = calendar(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20));
        when(serviceCalendarService.getOne(any(), eq(false))).thenReturn(calendar);
        assertThat(service.getOperationStatus(1L, LocalDate.of(2026, 9, 9)).reason())
                .isEqualTo("OUTSIDE_CALENDAR_RANGE");
        assertThat(service.getOperationStatus(1L, LocalDate.of(2026, 9, 14)).reason())
                .isEqualTo("REGULAR_SCHEDULE");
        assertThat(service.getOperationStatus(1L, LocalDate.of(2026, 9, 15)).reason())
                .isEqualTo("NOT_SCHEDULED_ON_WEEKDAY");
    }

    @Test
    void redisTripStateUpdatesAHashAndRefreshesTheTripExpiry() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(objectMapper.writeValueAsString(any())).thenReturn("serialized-trip");
        RedisLiveTripStateService service = new RedisLiveTripStateService(
                stringRedisTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);

        service.update(tripEvent("STOP-2", 2));

        verify(hashOperations).put("live:trip-delay:1:TRIP-1", "STOP-2:2", "serialized-trip");
        verify(stringRedisTemplate).expire("live:trip-delay:1:TRIP-1", java.time.Duration.ofSeconds(60));
    }

    @Test
    void redisTripStatePropagatesSerializationErrorsButIgnoresCorruptReadValues() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(objectMapper.writeValueAsString(any())).thenThrow(jsonError());
        RedisLiveTripStateService service = new RedisLiveTripStateService(
                stringRedisTemplate,
                objectMapper
        );

        assertThatThrownBy(() -> service.update(tripEvent("STOP-1", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize live trip state");

        LiveTripStopDelayResponse later = tripState("STOP-2", 2);
        LiveTripStopDelayResponse earlier = tripState("STOP-1", 1);
        when(hashOperations.entries("live:trip-delay:1:TRIP-1")).thenReturn(
                Map.of("one", "first", "two", "broken", "three", "last")
        );
        when(objectMapper.readValue("first", LiveTripStopDelayResponse.class)).thenReturn(later);
        when(objectMapper.readValue("broken", LiveTripStopDelayResponse.class)).thenThrow(jsonError());
        when(objectMapper.readValue("last", LiveTripStopDelayResponse.class)).thenReturn(earlier);

        assertThat(service.findByTrip(1L, "TRIP-1"))
                .extracting(LiveTripStopDelayResponse::stopSequence)
                .containsExactly(1, 2);
    }

    @Test
    void redisVehicleStateStoresCurrentPositionAndRejectsSerializationFailures() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(objectMapper.writeValueAsString(any())).thenReturn("serialized-vehicle");
        RedisLiveVehiclePositionService service = new RedisLiveVehiclePositionService(
                stringRedisTemplate,
                objectMapper
        );

        service.update(vehicleEvent("VEHICLE-1", "B"));
        verify(hashOperations).put("live:vehicles", "VEHICLE-1", "serialized-vehicle");

        when(objectMapper.writeValueAsString(any())).thenThrow(jsonError());
        assertThatThrownBy(() -> service.update(vehicleEvent("VEHICLE-2", "A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize live vehicle state");
    }

    @Test
    void redisVehicleStateSortsFreshValuesAndEvictsExpiredOrCorruptEntries() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        RedisLiveVehiclePositionService service = new RedisLiveVehiclePositionService(
                stringRedisTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);

        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("late-route", "fresh-b");
        entries.put("early-route", "fresh-a");
        entries.put("old", "stale");
        entries.put("bad", "corrupt");
        when(hashOperations.entries("live:vehicles")).thenReturn(entries);
        when(objectMapper.readValue("fresh-b", LiveVehiclePositionResponse.class))
                .thenReturn(vehicleState("VEHICLE-2", "B", OffsetDateTime.now(ZoneOffset.UTC)));
        when(objectMapper.readValue("fresh-a", LiveVehiclePositionResponse.class))
                .thenReturn(vehicleState("VEHICLE-1", "A", OffsetDateTime.now(ZoneOffset.UTC)));
        when(objectMapper.readValue("stale", LiveVehiclePositionResponse.class))
                .thenReturn(vehicleState("VEHICLE-3", "C", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2)));
        when(objectMapper.readValue("corrupt", LiveVehiclePositionResponse.class)).thenThrow(jsonError());

        assertThat(service.findAll())
                .extracting(LiveVehiclePositionResponse::externalRouteId)
                .containsExactly("A", "B");
        verify(hashOperations).delete(eq("live:vehicles"), any(Object[].class));
    }

    private TripOperationServiceImpl tripOperationService() {
        return new TripOperationServiceImpl(
                tripService,
                serviceCalendarService,
                serviceCalendarDateService
        );
    }

    private static RouteReliabilityResponse reliability(
            long routeId,
            long observations,
            BigDecimal averageDelay,
            BigDecimal p90Delay
    ) {
        return new RouteReliabilityResponse(
                routeId,
                "R" + routeId,
                "R" + routeId,
                "Route " + routeId,
                observations,
                averageDelay,
                0,
                p90Delay,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "HIGH"
        );
    }

    private static Trip trip() {
        Trip trip = new Trip();
        trip.setId(1L);
        trip.setFeedVersionId(2L);
        trip.setExternalTripId("TRIP-1");
        trip.setServiceId("WEEKDAY");
        return trip;
    }

    private static ServiceCalendar calendar(LocalDate start, LocalDate end) {
        ServiceCalendar calendar = new ServiceCalendar();
        calendar.setStartDate(start);
        calendar.setEndDate(end);
        calendar.setMonday(true);
        calendar.setTuesday(false);
        calendar.setWednesday(false);
        calendar.setThursday(false);
        calendar.setFriday(false);
        calendar.setSaturday(false);
        calendar.setSunday(false);
        return calendar;
    }

    private static TripUpdateEvent tripEvent(String stopId, int sequence) {
        return new TripUpdateEvent(
                UUID.randomUUID(),
                1L,
                "TRIP-1",
                stopId,
                sequence,
                120,
                Instant.parse("2026-09-11T12:00:00Z")
        );
    }

    private static LiveTripStopDelayResponse tripState(String stopId, int sequence) {
        return new LiveTripStopDelayResponse(
                UUID.randomUUID(), 1L, "TRIP-1", stopId, sequence, 120,
                Instant.now(), OffsetDateTime.now(ZoneOffset.UTC), "NTA_REALTIME"
        );
    }

    private static VehiclePositionEvent vehicleEvent(String vehicleId, String routeId) {
        return new VehiclePositionEvent(
                UUID.randomUUID(), vehicleId, "TRIP-1", routeId,
                "12:00:00", "20260911", (short) 0, 53.3, -6.2, 90.0,
                Instant.parse("2026-09-11T12:00:00Z")
        );
    }

    private static LiveVehiclePositionResponse vehicleState(
            String vehicleId,
            String routeId,
            OffsetDateTime cachedAt
    ) {
        return new LiveVehiclePositionResponse(
                UUID.randomUUID(), vehicleId, "TRIP-1", routeId,
                "12:00:00", "20260911", (short) 0, 53.3, -6.2, 90.0,
                Instant.now(), cachedAt, "NTA_VEHICLES_REALTIME"
        );
    }

    private static JsonProcessingException jsonError() {
        return new JsonProcessingException("Invalid JSON") {
        };
    }
}
