package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.response.NtaStaticGtfsSyncResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaStaticGtfsRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual inspection and synchronization endpoints for static GTFS versions. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/nta/static-gtfs")
public class NtaStaticGtfsController {

    private final NtaStaticGtfsRefreshService ntaStaticGtfsRefreshService;
    private final FeedVersionService feedVersionService;

    @PostMapping("/sync")
    public NtaStaticGtfsSyncResponse syncStaticGtfs() {
        return ntaStaticGtfsRefreshService.refreshIfChanged();
    }

    @GetMapping("/active")
    public FeedVersion getActiveFeedVersion() {
        return feedVersionService.findActive().orElse(null);
    }
}
