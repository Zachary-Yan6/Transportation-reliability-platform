package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.DelayBaselineStatisticsRow;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.dto.RouteDelayTrainingSampleResponse;
import com.zachary.transportation_reliability_platform.dto.RouteTrainingDataStatusRow;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.impl.DelayPredictionEvaluationServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.DelayPredictionServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.PredictionReadinessServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.PredictionTrainingDataServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests the explainable prediction pipeline without Kafka, Redis, or PostgreSQL. */
class PredictionServiceUnitTest {

    @Test
    void delayPredictorUsesTimeMatchedSamplesBeforeEveryFallback() {
        RouteService routeService = mock(RouteService.class);
        StopService stopService = mock(StopService.class);
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        OffsetDateTime target = OffsetDateTime.parse("2026-09-10T12:00:00Z");
        when(mapper.findTimeMatchedStopBaseline(eq(1L), eq(2L), anyInt(), anyInt(), eq(target)))
                .thenReturn(baseline(50, 120));

        DelayPredictionResponse response = new DelayPredictionServiceImpl(
                routeService, stopService, mapper
        ).predictDelay(1L, 2L, target);

        assertEquals("STOP_SAME_WEEKDAY_HOUR", response.dataSource());
        assertEquals("HIGH", response.confidence());
        assertEquals(BigDecimal.valueOf(120), response.predictedDelaySeconds());
        assertEquals(4, response.targetDayOfWeek());
        assertEquals(13, response.targetHour()); // 12:00 UTC is 13:00 in Dublin daylight time.
        verify(routeService).getRequiredById(1L);
        verify(stopService).getRequiredById(2L);
    }

    @Test
    void delayPredictorUsesStopThenRouteThenNoDataFallbacks() {
        OffsetDateTime target = OffsetDateTime.parse("2026-01-10T12:00:00Z");

        TripStopDelayObservationMapper stopMapper = mock(TripStopDelayObservationMapper.class);
        when(stopMapper.findTimeMatchedStopBaseline(anyLong(), anyLong(), anyInt(), anyInt(), eq(target)))
                .thenReturn(baseline(4, 10));
        when(stopMapper.findStopHistoryBaseline(1L, 2L, target)).thenReturn(baseline(20, 30));
        DelayPredictionResponse stopFallback = new DelayPredictionServiceImpl(
                mock(RouteService.class), mock(StopService.class), stopMapper
        ).predictDelay(1L, 2L, target);
        assertEquals("STOP_ALL_HOURS", stopFallback.dataSource());
        assertEquals("MEDIUM", stopFallback.confidence());

        TripStopDelayObservationMapper routeMapper = mock(TripStopDelayObservationMapper.class);
        when(routeMapper.findTimeMatchedStopBaseline(anyLong(), anyLong(), anyInt(), anyInt(), eq(target)))
                .thenReturn(null);
        when(routeMapper.findStopHistoryBaseline(1L, 2L, target)).thenReturn(baseline(2, 30));
        when(routeMapper.findRouteHistoryBaseline(1L, target)).thenReturn(baseline(1, 45));
        DelayPredictionResponse routeFallback = new DelayPredictionServiceImpl(
                mock(RouteService.class), mock(StopService.class), routeMapper
        ).predictDelay(1L, 2L, target);
        assertEquals("ROUTE_ALL_STOPS", routeFallback.dataSource());
        assertEquals("LOW", routeFallback.confidence());

        TripStopDelayObservationMapper emptyMapper = mock(TripStopDelayObservationMapper.class);
        DelayPredictionResponse noData = new DelayPredictionServiceImpl(
                mock(RouteService.class), mock(StopService.class), emptyMapper
        ).predictDelay(1L, 2L, target);
        assertEquals("NO_DATA", noData.dataSource());
        assertNull(noData.predictedDelaySeconds());
        assertEquals(0L, noData.matchedSampleCount());
    }

    @Test
    void readinessRequiresAllThreeQualityGates() {
        RouteService routeService = mock(RouteService.class);
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        when(mapper.getTrainingDataStatus(5L)).thenReturn(trainingStatus(999, "71.99", 47));
        PredictionReadinessServiceImpl service = new PredictionReadinessServiceImpl(routeService, mapper);

        var collecting = service.getRouteTrainingDataStatus(5L);
        assertFalse(collecting.readyForBaseline());
        assertTrue(collecting.recommendation().contains("Continue collecting Route 64"));

        when(mapper.getTrainingDataStatus(5L)).thenReturn(trainingStatus(1_000, "72", 48));
        var ready = service.getRouteTrainingDataStatus(5L);
        assertTrue(ready.readyForBaseline());
        assertTrue(ready.recommendation().contains("enough data"));
        verify(routeService, org.mockito.Mockito.times(2)).getRequiredById(5L);
    }

    @Test
    void trainingDataServiceClampsLimitsAndEscapesCsvValues() {
        RouteService routeService = mock(RouteService.class);
        TripStopDelayObservationMapper mapper = mock(TripStopDelayObservationMapper.class);
        RouteDelayTrainingSampleResponse sample = trainingSample(1, 100);
        sample.setExternalTripId("trip,\"quoted\"");
        sample.setExternalStopId("first\nsecond");
        when(mapper.findTrainingSamplesByRouteId(3L, 1)).thenReturn(List.of(sample));
        when(mapper.findTrainingSamplesByRouteId(3L, 10_000)).thenReturn(List.of(sample));
        PredictionTrainingDataServiceImpl service = new PredictionTrainingDataServiceImpl(routeService, mapper);

        assertEquals(1, service.getRouteTrainingSamples(3L, 0).size());
        String csv = service.exportRouteTrainingSamplesCsv(3L, 99_999);
        assertTrue(csv.startsWith("event_id,route_id"));
        assertTrue(csv.contains("\"trip,\"\"quoted\"\"\""));
        assertTrue(csv.contains("\"first\nsecond\""));
        verify(mapper).findTrainingSamplesByRouteId(3L, 1);
        verify(mapper).findTrainingSamplesByRouteId(3L, 10_000);
    }

    @Test
    void evaluationAvoidsWarmUpDataTracksSourcesAndSkipsNoDataPredictions() {
        RouteService routeService = mock(RouteService.class);
        PredictionTrainingDataService trainingDataService = mock(PredictionTrainingDataService.class);
        DelayPredictionService predictionService = mock(DelayPredictionService.class);
        List<RouteDelayTrainingSampleResponse> samples = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            samples.add(trainingSample(index, index == 8 ? 700 : 100));
        }
        when(trainingDataService.getRouteTrainingSamples(7L, 30)).thenReturn(samples);
        when(predictionService.predictDelay(eq(7L), anyLong(), any(OffsetDateTime.class)))
                .thenReturn(prediction(100, "STOP_SAME_WEEKDAY_HOUR"))
                .thenReturn(prediction(0, "STOP_ALL_HOURS"))
                .thenReturn(prediction(null, "NO_DATA"));

        var response = new DelayPredictionEvaluationServiceImpl(
                routeService, trainingDataService, predictionService
        ).evaluateRouteBaseline(7L, 0);

        assertEquals(3, response.candidateSampleCount());
        assertEquals(2, response.evaluatedSampleCount());
        assertEquals(1, response.skippedSampleCount());
        assertEquals(1, response.sameWeekdayHourPredictionCount());
        assertEquals(1, response.stopHistoryPredictionCount());
        assertEquals(0, response.routeFallbackPredictionCount());
        assertEquals(BigDecimal.valueOf(350).setScale(2), response.meanAbsoluteErrorSeconds());
        assertEquals(BigDecimal.valueOf(50).setScale(2), response.withinFiveMinutesPercentage());
        assertTrue(response.recommendation().contains("early indication"));
    }

    @Test
    void evaluationReturnsAnEmptyReportWhenThereIsNoTestWindow() {
        PredictionTrainingDataService trainingDataService = mock(PredictionTrainingDataService.class);
        when(trainingDataService.getRouteTrainingSamples(8L, 300)).thenReturn(List.of(trainingSample(1, 60)));

        var response = new DelayPredictionEvaluationServiceImpl(
                mock(RouteService.class), trainingDataService, mock(DelayPredictionService.class)
        ).evaluateRouteBaseline(8L, 999);

        assertEquals(0, response.evaluatedSampleCount());
        assertNull(response.meanAbsoluteErrorSeconds());
        assertTrue(response.recommendation().contains("Available samples: 1"));
    }

    @Test
    void evaluationDistinguishesPromisingAndHighErrorMatureBaselines() {
        List<RouteDelayTrainingSampleResponse> samples = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            samples.add(trainingSample(index, 100));
        }

        PredictionTrainingDataService promisingData = mock(PredictionTrainingDataService.class);
        DelayPredictionService promisingPredictor = mock(DelayPredictionService.class);
        when(promisingData.getRouteTrainingSamples(10L, 100)).thenReturn(samples);
        when(promisingPredictor.predictDelay(eq(10L), anyLong(), any(OffsetDateTime.class)))
                .thenReturn(prediction(100, "ROUTE_ALL_STOPS"));
        var promising = new DelayPredictionEvaluationServiceImpl(
                mock(RouteService.class), promisingData, promisingPredictor
        ).evaluateRouteBaseline(10L, 100);
        assertEquals(21, promising.evaluatedSampleCount());
        assertEquals(21, promising.routeFallbackPredictionCount());
        assertTrue(promising.recommendation().contains("promising"));

        PredictionTrainingDataService highErrorData = mock(PredictionTrainingDataService.class);
        DelayPredictionService highErrorPredictor = mock(DelayPredictionService.class);
        when(highErrorData.getRouteTrainingSamples(11L, 100)).thenReturn(samples);
        when(highErrorPredictor.predictDelay(eq(11L), anyLong(), any(OffsetDateTime.class)))
                .thenReturn(prediction(500, "STOP_SAME_WEEKDAY_HOUR"));
        var highError = new DelayPredictionEvaluationServiceImpl(
                mock(RouteService.class), highErrorData, highErrorPredictor
        ).evaluateRouteBaseline(11L, 100);
        assertTrue(highError.recommendation().contains("error is high"));
    }

    private static DelayBaselineStatisticsRow baseline(long samples, long averageDelaySeconds) {
        DelayBaselineStatisticsRow row = new DelayBaselineStatisticsRow();
        row.setSampleCount(samples);
        row.setAverageDelaySeconds(BigDecimal.valueOf(averageDelaySeconds));
        row.setP90DelaySeconds(BigDecimal.valueOf(averageDelaySeconds + 10));
        return row;
    }

    private static RouteTrainingDataStatusRow trainingStatus(
            long observations,
            String coverageHours,
            long activeHours
    ) {
        RouteTrainingDataStatusRow row = new RouteTrainingDataStatusRow();
        row.setRouteId(5L);
        row.setRouteShortName("64");
        row.setObservationCount(observations);
        row.setUniqueTripCount(4L);
        row.setUniqueStopCount(3L);
        row.setCoverageHours(new BigDecimal(coverageHours));
        row.setActiveHourCount(activeHours);
        return row;
    }

    private static RouteDelayTrainingSampleResponse trainingSample(int index, int actualDelaySeconds) {
        RouteDelayTrainingSampleResponse sample = new RouteDelayTrainingSampleResponse();
        sample.setEventId(UUID.nameUUIDFromBytes(("event-" + index).getBytes()));
        sample.setRouteId(7L);
        sample.setTripId((long) index + 10L);
        sample.setExternalTripId("TRIP-" + index);
        sample.setStopId((long) index + 20L);
        sample.setExternalStopId("STOP-" + index);
        sample.setStopSequence(index + 1);
        sample.setScheduledArrivalSeconds(3_600);
        sample.setScheduledDepartureSeconds(3_660);
        sample.setObservedDayOfWeek(3);
        sample.setObservedHour(12);
        sample.setObservedAt(OffsetDateTime.parse("2026-09-10T10:00:00Z").plusMinutes(index));
        sample.setActualDelaySeconds(actualDelaySeconds);
        return sample;
    }

    private static DelayPredictionResponse prediction(Integer seconds, String source) {
        return new DelayPredictionResponse(
                7L,
                20L,
                OffsetDateTime.parse("2026-09-10T10:00:00Z"),
                3,
                10,
                seconds == null ? null : BigDecimal.valueOf(seconds),
                null,
                seconds == null ? 0L : 10L,
                source,
                "LOW",
                "HISTORICAL_AVERAGE_BASELINE_V1",
                "Test prediction"
        );
    }
}
