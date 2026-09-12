package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.NtaServiceAlertResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaServiceAlertSyncResponse;
import com.zachary.transportation_reliability_platform.service.NtaServiceAlertService;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Inspection, synchronization, and dashboard endpoints for official NTA alerts. */

/**
 * no alert api
 * reviewed
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/nta/alerts")
public class NtaServiceAlertController {

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final NtaServiceAlertIngestionService ntaServiceAlertIngestionService;
    private final NtaServiceAlertService ntaServiceAlertService;

    @GetMapping("/raw")
    public String getRawAlertsFeed() {
        return ntaGtfsRealtimeClient.fetchRawAlertsFeed();
    }

    @PostMapping("/sync")
    public NtaServiceAlertSyncResponse syncAlerts() {
        return ntaServiceAlertIngestionService.synchronizeAlerts();
    }

    @GetMapping
    public List<NtaServiceAlertResponse> getAlerts(
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ntaServiceAlertService.findAlerts(activeOnly, limit);
    }
}
