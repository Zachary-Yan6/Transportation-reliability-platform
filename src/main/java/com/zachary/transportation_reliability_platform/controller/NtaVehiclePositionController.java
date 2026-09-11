package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.response.NtaVehiclePositionIngestionResponse;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaVehiclePositionIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development endpoints for checking and manually publishing NTA vehicles.
 */
@RestController
@RequestMapping("/api/v1/nta/vehicles")
@RequiredArgsConstructor
public class NtaVehiclePositionController {

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final NtaVehiclePositionIngestionService vehiclePositionIngestionService;

    /**
     * Returns the untouched NTA Vehicles JSON response for safe inspection.
     */
    @GetMapping("/raw")
    public String getRawVehiclesFeed() {
        return ntaGtfsRealtimeClient.fetchRawVehicleFeed();
    }

    /**
     * Publishes a controlled subset of one Vehicles snapshot to Kafka.
     */
    @PostMapping("/publish")
    public NtaVehiclePositionIngestionResponse publishVehiclePositions(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return vehiclePositionIngestionService.publishVehiclePositions(limit);
    }
}
