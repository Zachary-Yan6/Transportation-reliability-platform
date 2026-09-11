package com.zachary.transportation_reliability_platform.service.scheduler;

import com.zachary.transportation_reliability_platform.dto.response.NtaStaticGtfsSyncResponse;
import com.zachary.transportation_reliability_platform.service.impl.NtaStaticGtfsRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks NTA's static archive periodically; disabled until explicitly enabled. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.nta.static-gtfs.polling", name = "enabled", havingValue = "true")
public class NtaStaticGtfsPollingJob {

    private final NtaStaticGtfsRefreshService ntaStaticGtfsRefreshService;

    @Scheduled(
            initialDelayString = "${app.nta.static-gtfs.polling.initial-delay-ms}",
            fixedDelayString = "${app.nta.static-gtfs.polling.fixed-delay-ms}"
    )
    public void refreshStaticGtfs() {
        try {
            NtaStaticGtfsSyncResponse result = ntaStaticGtfsRefreshService.refreshIfChanged();
            log.info("Static GTFS refresh completed: status={}, feedVersion={}",
                    result.status(), result.feedVersionId());
        } catch (Exception exception) {
            log.error("Static GTFS refresh failed; the active feed remains unchanged", exception);
        }
    }
}
