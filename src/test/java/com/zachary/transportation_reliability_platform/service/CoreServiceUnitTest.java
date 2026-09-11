package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.DashboardStatisticsRow;
import com.zachary.transportation_reliability_platform.dto.StopTimeScheduleRow;
import com.zachary.transportation_reliability_platform.dto.TripReliabilityStatisticsRow;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.RealtimeIngestionRun;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;
import com.zachary.transportation_reliability_platform.mapper.FeedVersionMapper;
import com.zachary.transportation_reliability_platform.mapper.RealtimeIngestionRunMapper;
import com.zachary.transportation_reliability_platform.mapper.RouteMapper;
import com.zachary.transportation_reliability_platform.mapper.StopMapper;
import com.zachary.transportation_reliability_platform.mapper.StopTimeMapper;
import com.zachary.transportation_reliability_platform.mapper.TripMapper;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.impl.DashboardServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.FeedVersionServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.RealtimeIngestionRunServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.RouteReliabilityServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.RouteServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.StopServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.StopTimeServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.TripReliabilityServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.TripServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.TripStopDelayObservationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreServiceUnitTest {

    @ParameterizedTest
    @CsvSource({"0, 1, NO_DATA", "1, 1, LOW", "10, 10, MEDIUM", "50, 50, HIGH", "999, 168, HIGH"})
    void dashboardSummaryClampsHoursAndCalculatesConfidence(
            int requestedHours,
            int expectedHours,
            String expectedConfidence
    ) {
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        DashboardStatisticsRow statistics = dashboardStatistics(requestedHours == 0 ? 0L : requestedHours);
        when(mapper.calculateDashboardSummary(7L, expectedHours)).thenReturn(statistics);

        DashboardServiceImpl service = new DashboardServiceImpl(mapper);

        var response = service.getSummary(7L, requestedHours);

        assertEquals(expectedHours, response.hours());
        assertEquals(expectedConfidence, response.confidence());
        verify(mapper).calculateDashboardSummary(7L, expectedHours);
    }

    @ParameterizedTest
    @CsvSource({"0, NO_DATA", "1, LOW", "10, MEDIUM", "50, HIGH"})
    void tripReliabilityBuildsResponseForEveryConfidenceBand(long observations, String confidence) {
        TripService tripService = mock(TripService.class);
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        Trip trip = trip(8L, "trip-8");
        TripReliabilityStatisticsRow statistics = tripReliabilityStatistics(observations);
        when(tripService.getRequiredById(8L)).thenReturn(trip);
        when(mapper.calculateReliability(8L)).thenReturn(statistics);

        var response = new TripReliabilityServiceImpl(tripService, mapper).getTripReliability(8L);

        assertEquals("trip-8", response.externalTripId());
        assertEquals(confidence, response.confidence());
        assertEquals(response.onTimePercentage(), response.reliabilityScore());
    }

    @Test
    void routeReliabilityClampsEveryQueryLimitAndUsesRouteDetails() {
        RouteService routeService = mock(RouteService.class);
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        Route route = route(5L, "R5");
        when(routeService.getRequiredById(5L)).thenReturn(route);
        when(mapper.calculateReliabilityForRouteInWindow(5L, 168))
                .thenReturn(routeReliabilityStatistics(50L));
        when(mapper.findLeastReliableRoutes(3L, 1, 50)).thenReturn(List.of());
        when(mapper.findMostDelayedStops(5L, 1, 1)).thenReturn(List.of());
        when(mapper.findDelayTrendByRouteId(5L, 168)).thenReturn(List.of());
        RouteReliabilityServiceImpl service = new RouteReliabilityServiceImpl(routeService, mapper);

        var reliability = service.getRouteReliability(5L, 999);

        assertEquals("R5", reliability.externalRouteId());
        assertEquals("HIGH", reliability.confidence());
        assertTrue(service.getLeastReliableRoutes(3L, 0, 999).isEmpty());
        assertTrue(service.getMostDelayedStops(5L, 0, 0).isEmpty());
        assertTrue(service.getDelayTrend(5L, 999).isEmpty());
        verify(mapper).calculateReliabilityForRouteInWindow(5L, 168);
        verify(mapper).findLeastReliableRoutes(3L, 1, 50);
        verify(mapper).findMostDelayedStops(5L, 1, 1);
        verify(mapper).findDelayTrendByRouteId(5L, 168);
    }

    @Test
    void stopTimeScheduleFormatsNullAndGtfsTimes() {
        StopTimeMapper mapper = mock(StopTimeMapper.class);
        StopTimeScheduleRow midnight = scheduleRow(1, "STOP-1", 0, 26 * 3600 + 61);
        StopTimeScheduleRow missingArrival = scheduleRow(2, "STOP-2", null, null);
        when(mapper.findScheduleByTripId(3L)).thenReturn(List.of(midnight, missingArrival));
        when(mapper.existsForFeedVersionId(4L)).thenReturn(true);
        StopTimeServiceImpl service = new StopTimeServiceImpl(mapper);

        var schedule = service.getScheduleByTripId(3L);

        assertEquals("00:00:00", schedule.get(0).arrivalTime());
        assertEquals("26:01:01", schedule.get(0).departureTime());
        assertNull(schedule.get(1).arrivalTime());
        assertNull(schedule.get(1).departureTime());
        assertTrue(service.existsForFeedVersionId(4L));
    }

    @Test
    void requiredEntityServicesReturnEntityOrNotFound() {
        TripMapper tripMapper = mock(TripMapper.class);
        StopMapper stopMapper = mock(StopMapper.class);
        RouteMapper routeMapper = mock(RouteMapper.class);
        FeedVersionMapper feedMapper = mock(FeedVersionMapper.class);
        when(tripMapper.selectById(1L)).thenReturn(trip(1L, "T1"));
        when(stopMapper.selectById(2L)).thenReturn(stop(2L, "S2"));
        when(routeMapper.selectById(3L)).thenReturn(route(3L, "R3"));
        when(feedMapper.selectById(4L)).thenReturn(feedVersion(4L, "IMPORTING"));

        TripServiceImpl tripService = withBaseMapper(new TripServiceImpl(), tripMapper);
        StopServiceImpl stopService = withBaseMapper(new StopServiceImpl(), stopMapper);
        RouteServiceImpl routeService = withBaseMapper(new RouteServiceImpl(), routeMapper);
        FeedVersionServiceImpl feedVersionService = withBaseMapper(new FeedVersionServiceImpl(), feedMapper);

        assertEquals("T1", tripService.getRequiredById(1L).getExternalTripId());
        assertEquals("S2", stopService.getRequiredById(2L).getExternalStopId());
        assertEquals("R3", routeService.getRequiredById(3L).getExternalRouteId());
        assertEquals("IMPORTING", feedVersionService.getRequiredById(4L).getLifecycleStatus());
        assertThrows(BusinessException.class, () -> tripService.getRequiredById(99L));
        assertThrows(BusinessException.class, () -> stopService.getRequiredById(99L));
        assertThrows(BusinessException.class, () -> routeService.getRequiredById(99L));
        assertThrows(BusinessException.class, () -> feedVersionService.getRequiredById(99L));
    }

    @Test
    void stopSearchRejectsBlankTextBeforeQueryingDatabase() {
        StopMapper mapper = mock(StopMapper.class);
        StopServiceImpl service = withBaseMapper(new StopServiceImpl(), mapper);

        assertTrue(service.searchByName(null).isEmpty());
        assertTrue(service.searchByName("   ").isEmpty());
    }

    @Test
    void delayObservationServiceValidatesLimitsAndUsesIdempotentInsert() {
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        TripStopDelayObservationServiceImpl service = withBaseMapper(
                new TripStopDelayObservationServiceImpl(), mapper
        );
        UUID eventId = UUID.randomUUID();
        TripStopDelayObservation observation = new TripStopDelayObservation();
        observation.setEventId(eventId);
        when(mapper.insertIfAbsent(observation)).thenReturn(1);
        when(mapper.findRecentByTripId(1L, 10)).thenReturn(List.of());
        when(mapper.findRecentByStopId(2L, 10)).thenReturn(List.of());

        assertTrue(service.saveIfAbsent(observation));
        assertTrue(service.findRecentByTripId(1L, 10).isEmpty());
        assertTrue(service.findRecentByStopId(2L, 10).isEmpty());
        assertThrows(BusinessException.class, () -> service.findRecentByTripId(1L, 0));
        assertThrows(BusinessException.class, () -> service.findRecentByTripId(1L, 101));
        assertThrows(BusinessException.class, () -> service.findRecentByStopId(2L, 0));
        assertThrows(BusinessException.class, () -> service.findRecentByStopId(2L, 101));
        verify(mapper).insertIfAbsent(observation);
    }

    @Test
    void ingestionRunServicePersistsSuccessAndFailureAuditRecords() {
        RealtimeIngestionRunMapper mapper = mock(RealtimeIngestionRunMapper.class);
        when(mapper.insert(any(RealtimeIngestionRun.class))).thenReturn(1);
        RealtimeIngestionRunServiceImpl service = withBaseMapper(
                new RealtimeIngestionRunServiceImpl(), mapper
        );
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        NtaRealtimeIngestionResponse result = new NtaRealtimeIngestionResponse(
                Instant.parse("2026-09-10T09:59:00Z"), 12, 8, 4, List.of()
        );

        service.recordSuccess(1L, "R1", start, result);
        service.recordFailure(1L, "R1", start, new IllegalArgumentException());

        ArgumentCaptor<RealtimeIngestionRun> runs = ArgumentCaptor.forClass(RealtimeIngestionRun.class);
        verify(mapper, times(2)).insert(runs.capture());
        RealtimeIngestionRun success = runs.getAllValues().get(0);
        RealtimeIngestionRun failure = runs.getAllValues().get(1);
        assertEquals("SUCCESS", success.getStatus());
        assertEquals(12, success.getScannedStopTimeUpdates());
        assertEquals("FAILED", failure.getStatus());
        assertEquals("IllegalArgumentException", failure.getErrorMessage());
    }

    private static DashboardStatisticsRow dashboardStatistics(long observationCount) {
        DashboardStatisticsRow statistics = new DashboardStatisticsRow();
        statistics.setObservationCount(observationCount);
        statistics.setMonitoredTripCount(2L);
        statistics.setMonitoredRouteCount(1L);
        statistics.setAverageDelaySeconds(BigDecimal.TEN);
        statistics.setOnTimePercentage(BigDecimal.valueOf(80));
        return statistics;
    }

    private static TripReliabilityStatisticsRow tripReliabilityStatistics(long observationCount) {
        TripReliabilityStatisticsRow statistics = new TripReliabilityStatisticsRow();
        statistics.setObservationCount(observationCount);
        statistics.setAverageDelaySeconds(BigDecimal.TEN);
        statistics.setMaximumDelaySeconds(30);
        statistics.setP90DelaySeconds(BigDecimal.valueOf(40));
        statistics.setOnTimePercentage(BigDecimal.valueOf(75));
        return statistics;
    }

    private static com.zachary.transportation_reliability_platform.dto.RouteReliabilityStatisticsRow routeReliabilityStatistics(long observationCount) {
        com.zachary.transportation_reliability_platform.dto.RouteReliabilityStatisticsRow statistics =
                new com.zachary.transportation_reliability_platform.dto.RouteReliabilityStatisticsRow();
        statistics.setObservationCount(observationCount);
        statistics.setAverageDelaySeconds(BigDecimal.TEN);
        statistics.setMaximumDelaySeconds(30);
        statistics.setP90DelaySeconds(BigDecimal.valueOf(40));
        statistics.setOnTimePercentage(BigDecimal.valueOf(75));
        return statistics;
    }

    private static StopTimeScheduleRow scheduleRow(
            int sequence,
            String externalStopId,
            Integer arrival,
            Integer departure
    ) {
        StopTimeScheduleRow row = new StopTimeScheduleRow();
        row.setStopSequence(sequence);
        row.setExternalStopId(externalStopId);
        row.setStopName("Stop " + sequence);
        row.setArrivalSeconds(arrival);
        row.setDepartureSeconds(departure);
        return row;
    }

    private static Trip trip(long id, String externalTripId) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setExternalTripId(externalTripId);
        trip.setFeedVersionId(1L);
        return trip;
    }

    private static Stop stop(long id, String externalStopId) {
        Stop stop = new Stop();
        stop.setId(id);
        stop.setExternalStopId(externalStopId);
        return stop;
    }

    private static Route route(long id, String externalRouteId) {
        Route route = new Route();
        route.setId(id);
        route.setExternalRouteId(externalRouteId);
        route.setShortName(externalRouteId);
        route.setLongName("Route " + externalRouteId);
        return route;
    }

    private static FeedVersion feedVersion(long id, String lifecycleStatus) {
        FeedVersion version = new FeedVersion();
        version.setId(id);
        version.setLifecycleStatus(lifecycleStatus);
        return version;
    }

    private static <T> T withBaseMapper(T service, Object mapper) {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }
}
