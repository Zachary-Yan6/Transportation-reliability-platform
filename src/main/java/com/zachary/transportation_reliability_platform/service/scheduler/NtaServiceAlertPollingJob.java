package com.zachary.transportation_reliability_platform.service.scheduler;

import com.zachary.transportation_reliability_platform.dto.response.NtaServiceAlertSyncResponse;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically refreshes official service disruptions without blocking other feeds. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.nta.alert-polling", name = "enabled", havingValue = "true")
public class NtaServiceAlertPollingJob {

    private final NtaServiceAlertIngestionService ntaServiceAlertIngestionService;

    @Scheduled(
            initialDelayString = "${app.nta.alert-polling.initial-delay-ms}",
            fixedDelayString = "${app.nta.alert-polling.fixed-delay-ms}"
    )
    public void pollAlerts() {
        try {
            NtaServiceAlertSyncResponse result = ntaServiceAlertIngestionService.synchronizeAlerts();
            log.info("NTA alerts synchronized: saved={}, resolved={}, scanned={}",
                    result.savedAlertCount(), result.resolvedAlertCount(), result.scannedAlertCount());
        } catch (Exception exception) {
            log.error("NTA alert polling failed", exception);
        }
    }
}
