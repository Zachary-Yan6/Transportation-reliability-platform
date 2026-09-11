package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.ServiceAlertResponse;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Returns persisted operational alerts for the dashboard.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class ServiceAlertController {

    private final ServiceAlertService serviceAlertService;

    @GetMapping
    public List<ServiceAlertResponse> getRecentAlerts(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return serviceAlertService.findRecentAlerts(limit);
    }
}
