package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.DashboardStatisticsRow;
import com.zachary.transportation_reliability_platform.dto.DashboardSummaryResponse;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(
            Long feedVersionId,
            int hours
    ) {
        // Limit the time window to one hour through seven days.
        int safeHours = Math.min(Math.max(hours, 1), 168);

        DashboardStatisticsRow statistics =
                delayObservationMapper.calculateDashboardSummary(
                        feedVersionId,
                        safeHours
                );

        return new DashboardSummaryResponse(
                feedVersionId,
                safeHours,
                statistics.getObservationCount(),
                statistics.getMonitoredTripCount(),
                statistics.getMonitoredRouteCount(),
                statistics.getAverageDelaySeconds(),
                statistics.getOnTimePercentage(),
                statistics.getLatestObservedAt(),
                calculateConfidence(statistics.getObservationCount())
        );
    }

    private String calculateConfidence(Long observationCount) {
        if (observationCount == null || observationCount == 0) {
            return "NO_DATA";
        }

        if (observationCount < 10) {
            return "LOW";
        }

        if (observationCount < 50) {
            return "MEDIUM";
        }

        return "HIGH";
    }
}