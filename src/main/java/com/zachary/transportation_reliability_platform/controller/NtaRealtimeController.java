package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.RealtimeIngestionRunResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaFeedSummaryResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaTripUpdatePreviewResponse;
import com.zachary.transportation_reliability_platform.entity.RealtimeIngestionRun;
import com.zachary.transportation_reliability_platform.service.RealtimeIngestionRunService;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeIngestionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeInspectionService;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/nta/realtime")
@RequiredArgsConstructor
public class NtaRealtimeController {

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final NtaRealtimeInspectionService ntaRealtimeInspectionService;
    private final NtaRealtimeIngestionService ntaRealtimeIngestionService;
    private final RealtimeIngestionRunService realtimeIngestionRunService;
    /**
     * Returns the untouched JSON received from NTA.
     */
    @GetMapping("/raw")
    public String getRawFeed() {
        return ntaGtfsRealtimeClient.fetchRawFeed();
    }

    /**
     * Returns counts of trip updates, vehicles, and alerts in the live feed.
     */
    @GetMapping("/summary")
    public NtaFeedSummaryResponse getFeedSummary() {
        return ntaRealtimeInspectionService.inspectFeed();
    }

    @GetMapping("/trip-updates/preview")
    public List<NtaTripUpdatePreviewResponse> previewTripUpdates(
            @RequestParam(defaultValue = "5") int limit
    ) {
        return ntaRealtimeInspectionService.previewTripUpdates(limit);
    }

    /**
     * Publishes a small number of valid NTA live updates to Kafka.
     */
    @PostMapping("/trip-updates/publish")
    public NtaRealtimeIngestionResponse publishTripUpdates(
            @RequestParam Long feedVersionId,
            @RequestParam(defaultValue = "3") int limit,
            @RequestParam(required = false) String targetExternalRouteId
    ) {
        return ntaRealtimeIngestionService.publishValidUpdates(
                feedVersionId,
                limit,
                targetExternalRouteId
        );
    }

    /**
     * Returns recent automatic NTA polling attempts.
     *
     * This lets the frontend show whether live data collection is healthy.
     */
    @GetMapping("/polling-runs")
    public List<RealtimeIngestionRunResponse> getRecentPollingRuns(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return realtimeIngestionRunService.findRecent(limit)
                .stream()
                .map(this::toPollingRunResponse)
                .toList();
    }

    /**
     * Converts a database audit entity into an API-safe response.
     */
    private RealtimeIngestionRunResponse toPollingRunResponse(
            RealtimeIngestionRun run
    ) {
        long durationMilliseconds = Duration.between(
                run.getStartedAt(),
                run.getFinishedAt()
        ).toMillis();

        return new RealtimeIngestionRunResponse(
                run.getId(),
                run.getTargetExternalRouteId(),
                run.getFeedTimestamp(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getScannedStopTimeUpdates(),
                run.getPublishedEventCount(),
                run.getSkippedUpdateCount(),
                run.getStatus(),
                run.getErrorMessage(),
                durationMilliseconds
        );
    }

}