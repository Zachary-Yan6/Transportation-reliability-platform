package com.zachary.transportation_reliability_platform.service.scheduler;

import com.zachary.transportation_reliability_platform.dto.response.NtaVehiclePositionIngestionResponse;
import com.zachary.transportation_reliability_platform.service.impl.NtaVehiclePositionIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls NTA's independent Vehicles operation on the configured interval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.nta.vehicle-position-polling",
        name = "enabled",
        havingValue = "true"
)
public class NtaVehiclePositionPollingJob {

    private final NtaVehiclePositionIngestionService vehiclePositionIngestionService;

    @Scheduled(
            initialDelayString = "${app.nta.vehicle-position-polling.initial-delay-ms}",
            fixedDelayString = "${app.nta.vehicle-position-polling.fixed-delay-ms}"
    )
    public void pollVehiclePositions() {
        try {
            NtaVehiclePositionIngestionResponse result =
                    vehiclePositionIngestionService.publishAllVehiclePositions();

            log.info(
                    "NTA vehicle polling completed: published={}, skipped={}, scanned={}",
                    result.publishedEventCount(),
                    result.skippedVehicleCount(),
                    result.scannedVehicleCount()
            );
        } catch (Exception exception) {
            // Vehicle maps can temporarily be unavailable without interrupting
            // the separate trip-delay ingestion pipeline.
            log.error("NTA vehicle polling failed", exception);
        }
    }
}
