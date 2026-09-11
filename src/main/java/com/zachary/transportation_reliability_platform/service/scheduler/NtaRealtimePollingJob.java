package com.zachary.transportation_reliability_platform.service.scheduler;

import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RealtimeIngestionRunService;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.nta.realtime-polling",
        name = "enabled",
        havingValue = "true"
)
public class NtaRealtimePollingJob {

    private final NtaRealtimeIngestionService ntaRealtimeIngestionService;
    private final FeedVersionService feedVersionService;

    @Value("${app.nta.realtime-polling.feed-version-id}")
    private Long feedVersionId;

    @Value("${app.nta.realtime-polling.max-events-per-run}")
    private int maxEventsPerRun;

    @Value("${app.nta.realtime-polling.target-external-route-id:}")
    private String targetExternalRouteId;

    @Value("${app.nta.realtime-polling.publication-batch-size:250}")
    private int publicationBatchSize;

    private final RealtimeIngestionRunService realtimeIngestionRunService;
    /**
     * Polls NTA every five minutes and publishes valid updates to Kafka.
     */
    @Scheduled(
            initialDelayString = "${app.nta.realtime-polling.initial-delay-ms}",
            fixedDelayString = "${app.nta.realtime-polling.fixed-delay-ms}"
    )
    public void pollNtaRealtimeFeed() {
        Instant startedAt = Instant.now();
        Long activeFeedVersionId = activeFeedVersionId();

        try {
            NtaRealtimeIngestionResponse result =
                    ntaRealtimeIngestionService.publishValidUpdates(
                            activeFeedVersionId,
                            maxEventsPerRun,
                            targetExternalRouteId,
                            publicationBatchSize
                    );

            realtimeIngestionRunService.recordSuccess(
                    activeFeedVersionId,
                    auditTargetExternalRouteId(),
                    startedAt,
                    result
            );

            log.info(
                    "NTA polling completed for {}: published={}, skipped={}, scanned={}",
                    pollingScope(),
                    result.publishedEventCount(),
                    result.skippedUpdateCount(),
                    result.scannedStopTimeUpdates()
            );

        } catch (Exception exception) {
            realtimeIngestionRunService.recordFailure(
                    activeFeedVersionId,
                    auditTargetExternalRouteId(),
                    startedAt,
                    exception
            );

            log.error(
                    "NTA polling failed for {}",
                    pollingScope(),
                    exception
            );
        }
    }

    private String pollingScope() {
        return targetExternalRouteId == null || targetExternalRouteId.isBlank()
                ? "all imported routes"
                : "route " + targetExternalRouteId;
    }

    /**
     * The configuration value bootstraps old installations. Once automatic
     * static synchronization activates a replacement, polling follows that
     * database-backed selection rather than requiring an application restart.
     */
    private Long activeFeedVersionId() {
        return feedVersionService.findActive()
                .map(feedVersion -> feedVersion.getId())
                .orElse(feedVersionId);
    }

    /**
     * A null audit value explicitly represents the all-routes scope.
     */
    private String auditTargetExternalRouteId() {
        return targetExternalRouteId == null || targetExternalRouteId.isBlank()
                ? null
                : targetExternalRouteId.trim();
    }
}
