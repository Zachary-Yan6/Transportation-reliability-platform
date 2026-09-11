package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.DashboardSummaryResponse;
import com.zachary.transportation_reliability_platform.service.DashboardService;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final FeedVersionService feedVersionService;

    /**
     * Returns recent overall reliability statistics.
     */
    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary(
            @RequestParam(required = false) Long feedVersionId,
            @RequestParam(defaultValue = "24") int hours
    ) {
        return dashboardService.getSummary(resolveFeedVersionId(feedVersionId), hours);
    }

    private Long resolveFeedVersionId(Long requestedFeedVersionId) {
        if (requestedFeedVersionId != null) {
            return requestedFeedVersionId;
        }

        return feedVersionService.findActive()
                .map(feedVersion -> feedVersion.getId())
                .orElseThrow(() -> new IllegalStateException("No active GTFS feed version is available"));
    }
}
