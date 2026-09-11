package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaServiceAlertSyncResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaStaticGtfsSyncResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaVehiclePositionIngestionResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeIngestionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertIngestionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaStaticGtfsRefreshService;
import com.zachary.transportation_reliability_platform.service.impl.NtaVehiclePositionIngestionService;
import com.zachary.transportation_reliability_platform.service.scheduler.NtaRealtimePollingJob;
import com.zachary.transportation_reliability_platform.service.scheduler.NtaServiceAlertPollingJob;
import com.zachary.transportation_reliability_platform.service.scheduler.NtaStaticGtfsPollingJob;
import com.zachary.transportation_reliability_platform.service.scheduler.NtaVehiclePositionPollingJob;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled jobs are unit tested as ordinary methods. That verifies their
 * failure isolation without waiting for Spring's scheduler or real NTA calls.
 */
class PollingJobsUnitTest {

    @Test
    void realtimePollingUsesTheActiveFeedAndRecordsACompleteSuccessAudit() {
        NtaRealtimeIngestionService ingestionService =
                mock(NtaRealtimeIngestionService.class);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RealtimeIngestionRunService auditService = mock(RealtimeIngestionRunService.class);
        FeedVersion activeFeed = new FeedVersion();
        activeFeed.setId(9L);
        when(feedVersionService.findActive()).thenReturn(Optional.of(activeFeed));
        NtaRealtimeIngestionResponse result = new NtaRealtimeIngestionResponse(
                Instant.parse("2026-09-11T12:00:00Z"), 5, 3, 2, List.of()
        );
        when(ingestionService.publishValidUpdates(9L, 100, "ROUTE-1", 25))
                .thenReturn(result);
        NtaRealtimePollingJob job = new NtaRealtimePollingJob(
                ingestionService, feedVersionService, auditService
        );
        ReflectionTestUtils.setField(job, "feedVersionId", 1L);
        ReflectionTestUtils.setField(job, "maxEventsPerRun", 100);
        ReflectionTestUtils.setField(job, "targetExternalRouteId", "ROUTE-1");
        ReflectionTestUtils.setField(job, "publicationBatchSize", 25);

        job.pollNtaRealtimeFeed();

        verify(ingestionService).publishValidUpdates(9L, 100, "ROUTE-1", 25);
        verify(auditService).recordSuccess(eq(9L), eq("ROUTE-1"), any(), eq(result));
    }

    @Test
    void realtimePollingRecordsFailureButLeavesTheSchedulerHealthy() {
        NtaRealtimeIngestionService ingestionService =
                mock(NtaRealtimeIngestionService.class);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RealtimeIngestionRunService auditService = mock(RealtimeIngestionRunService.class);
        when(feedVersionService.findActive()).thenReturn(Optional.empty());
        when(ingestionService.publishValidUpdates(1L, 10, " ", 20))
                .thenThrow(new IllegalStateException("NTA unavailable"));
        NtaRealtimePollingJob job = new NtaRealtimePollingJob(
                ingestionService, feedVersionService, auditService
        );
        ReflectionTestUtils.setField(job, "feedVersionId", 1L);
        ReflectionTestUtils.setField(job, "maxEventsPerRun", 10);
        ReflectionTestUtils.setField(job, "targetExternalRouteId", " ");
        ReflectionTestUtils.setField(job, "publicationBatchSize", 20);

        job.pollNtaRealtimeFeed();

        verify(auditService).recordFailure(eq(1L), isNull(), any(), any());
    }

    @Test
    void vehicleAlertAndStaticFeedJobsInvokeTheirWorkersAndContainExceptions() {
        NtaVehiclePositionIngestionService vehicleService =
                mock(NtaVehiclePositionIngestionService.class);
        NtaServiceAlertIngestionService alertService =
                mock(NtaServiceAlertIngestionService.class);
        NtaStaticGtfsRefreshService staticService =
                mock(NtaStaticGtfsRefreshService.class);
        when(vehicleService.publishAllVehiclePositions()).thenReturn(
                new NtaVehiclePositionIngestionResponse(Instant.now(), 2, 1, 1, List.of())
        );
        when(alertService.synchronizeAlerts()).thenReturn(
                new NtaServiceAlertSyncResponse(Instant.now(), 2, 1, 1)
        );
        when(staticService.refreshIfChanged()).thenReturn(
                new NtaStaticGtfsSyncResponse(
                        "UNCHANGED", 1L, "checksum", null,
                        0, 0, 0, 0, 0, 0
                )
        );

        new NtaVehiclePositionPollingJob(vehicleService).pollVehiclePositions();
        new NtaServiceAlertPollingJob(alertService).pollAlerts();
        new NtaStaticGtfsPollingJob(staticService).refreshStaticGtfs();

        verify(vehicleService).publishAllVehiclePositions();
        verify(alertService).synchronizeAlerts();
        verify(staticService).refreshIfChanged();

        when(vehicleService.publishAllVehiclePositions())
                .thenThrow(new IllegalStateException("temporary failure"));
        when(alertService.synchronizeAlerts())
                .thenThrow(new IllegalStateException("temporary failure"));
        when(staticService.refreshIfChanged())
                .thenThrow(new IllegalStateException("temporary failure"));

        new NtaVehiclePositionPollingJob(vehicleService).pollVehiclePositions();
        new NtaServiceAlertPollingJob(alertService).pollAlerts();
        new NtaStaticGtfsPollingJob(staticService).refreshStaticGtfs();
    }
}
