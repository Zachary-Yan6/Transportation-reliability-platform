package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.entity.RealtimeIngestionRun;
import com.zachary.transportation_reliability_platform.service.DelayPredictionEvaluationService;
import com.zachary.transportation_reliability_platform.service.DelayPredictionService;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.NtaServiceAlertService;
import com.zachary.transportation_reliability_platform.service.PredictionReadinessService;
import com.zachary.transportation_reliability_platform.service.PredictionTrainingDataService;
import com.zachary.transportation_reliability_platform.service.RealtimeIngestionRunService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayEstimateService;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeIngestionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeInspectionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertIngestionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaStaticGtfsRefreshService;
import com.zachary.transportation_reliability_platform.service.impl.NtaVehiclePositionIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.zachary.transportation_reliability_platform.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice tests for NTA, AI-data, and delay-estimate endpoints.
 * Collaborators are mocked so these tests exercise request binding and HTTP
 * contracts without calling NTA, Redis, Kafka, or PostgreSQL.
 */
@WebMvcTest(controllers = {
        NtaRealtimeController.class,
        NtaServiceAlertController.class,
        NtaStaticGtfsController.class,
        NtaVehiclePositionController.class,
        PredictionReadinessController.class,
        DelayPredictionController.class,
        TripStopDelayEstimateController.class
}, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = JwtAuthenticationFilter.class
))
@AutoConfigureMockMvc(addFilters = false)
class ExternalDataControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    @MockBean
    private NtaRealtimeInspectionService ntaRealtimeInspectionService;
    @MockBean
    private NtaRealtimeIngestionService ntaRealtimeIngestionService;
    @MockBean
    private RealtimeIngestionRunService realtimeIngestionRunService;
    @MockBean
    private NtaServiceAlertIngestionService ntaServiceAlertIngestionService;
    @MockBean
    private NtaServiceAlertService ntaServiceAlertService;
    @MockBean
    private NtaStaticGtfsRefreshService ntaStaticGtfsRefreshService;
    @MockBean
    private FeedVersionService feedVersionService;
    @MockBean
    private NtaVehiclePositionIngestionService ntaVehiclePositionIngestionService;
    @MockBean
    private PredictionReadinessService predictionReadinessService;
    @MockBean
    private PredictionTrainingDataService predictionTrainingDataService;
    @MockBean
    private DelayPredictionService delayPredictionService;
    @MockBean
    private DelayPredictionEvaluationService delayPredictionEvaluationService;
    @MockBean
    private TripStopDelayEstimateService tripStopDelayEstimateService;

    @Test
    void realtimeEndpointsUseRequestDefaultsAndConvertPollingRunDurations() throws Exception {
        when(ntaGtfsRealtimeClient.fetchRawFeed()).thenReturn("{\"entity\":[]}");
        when(ntaRealtimeInspectionService.previewTripUpdates(5)).thenReturn(List.of());
        when(realtimeIngestionRunService.findRecent(20)).thenReturn(List.of(run()));

        mockMvc.perform(get("/api/v1/nta/realtime/raw"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"entity\":[]}"));
        mockMvc.perform(get("/api/v1/nta/realtime/trip-updates/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(post("/api/v1/nta/realtime/trip-updates/publish")
                        .param("feedVersionId", "8"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/nta/realtime/polling-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].durationMilliseconds").value(2500));

        verify(ntaRealtimeIngestionService).publishValidUpdates(8L, 3, null);
        verify(realtimeIngestionRunService).findRecent(20);
    }

    @Test
    void serviceAlertAndVehicleEndpointsDelegateWithTheirDefaults() throws Exception {
        when(ntaGtfsRealtimeClient.fetchRawAlertsFeed()).thenReturn("{\"entity\":[]}");
        when(ntaGtfsRealtimeClient.fetchRawVehicleFeed()).thenReturn("{\"entity\":[]}");
        when(ntaServiceAlertService.findAlerts(true, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/nta/alerts/raw"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"entity\":[]}"));
        mockMvc.perform(post("/api/v1/nta/alerts/sync"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/nta/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/nta/vehicles/raw"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"entity\":[]}"));
        mockMvc.perform(post("/api/v1/nta/vehicles/publish"))
                .andExpect(status().isOk());

        verify(ntaServiceAlertService).findAlerts(true, 50);
        verify(ntaVehiclePositionIngestionService).publishVehiclePositions(10);
    }

    @Test
    void staticGtfsEndpointsReturnTheCurrentVersionOrStartASync() throws Exception {
        mockMvc.perform(post("/api/v1/nta/static-gtfs/sync"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/nta/static-gtfs/active"))
                .andExpect(status().isOk());

        verify(ntaStaticGtfsRefreshService).refreshIfChanged();
        verify(feedVersionService).findActive();
    }

    @Test
    void trainingSampleEndpointsRespectLimitsAndExposeCsvAsADownload() throws Exception {
        when(predictionTrainingDataService.getRouteTrainingSamples(3L, 500))
                .thenReturn(List.of());
        when(predictionTrainingDataService.exportRouteTrainingSamplesCsv(3L, 10000, 0))
                .thenReturn("stop_id,actual_delay_seconds\n1,20\n");

        mockMvc.perform(get("/api/v1/ai/routes/3/training-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/ai/routes/3/training-samples.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"route-3-delay-training-samples.csv\""));

        verify(predictionTrainingDataService).getRouteTrainingSamples(3L, 500);
        verify(predictionTrainingDataService).exportRouteTrainingSamplesCsv(3L, 10000, 0);
    }

    @Test
    void readyRoutesEndpointDelegatesToTheReadinessService() throws Exception {
        when(predictionReadinessService.getRoutesReadyForTraining())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ai/routes/ready-for-training"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(predictionReadinessService).getRoutesReadyForTraining();
    }

    @Test
    void predictionAndTripEstimateEndpointsBindExplicitTimes() throws Exception {
        OffsetDateTime target = OffsetDateTime.of(
                2026, 9, 11, 13, 0, 0, 0, ZoneOffset.UTC
        );

        mockMvc.perform(get("/api/v1/ai/routes/7/stops/9/delay-prediction")
                        .param("targetTime", target.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ai/routes/7/delay-prediction/evaluation"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trips/4/stops/9/delay-estimate")
                        .param("stopSequence", "2")
                        .param("targetTime", target.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trips/4/delay-estimates")
                        .param("targetTime", target.toString()))
                .andExpect(status().isOk());

        verify(delayPredictionService).predictDelay(7L, 9L, target);
        verify(delayPredictionEvaluationService).evaluateRouteBaseline(7L, 200);
        verify(tripStopDelayEstimateService).estimateDelay(4L, 9L, 2, target);
        verify(tripStopDelayEstimateService).estimateDelaysForTrip(4L, target);
    }

    private static RealtimeIngestionRun run() {
        RealtimeIngestionRun run = new RealtimeIngestionRun();
        run.setId(1L);
        run.setStartedAt(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        run.setFinishedAt(OffsetDateTime.parse("2026-09-11T12:00:02.500Z"));
        run.setFeedTimestamp(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        run.setScannedStopTimeUpdates(1);
        run.setPublishedEventCount(1);
        run.setSkippedUpdateCount(0);
        run.setStatus("SUCCESS");
        return run;
    }
}
