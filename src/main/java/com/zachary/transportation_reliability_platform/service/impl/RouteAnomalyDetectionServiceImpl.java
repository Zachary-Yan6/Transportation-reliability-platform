package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;
import com.zachary.transportation_reliability_platform.dto.RouteReliabilityResponse;
import com.zachary.transportation_reliability_platform.service.RouteAnomalyDetectionService;
import com.zachary.transportation_reliability_platform.service.RouteReliabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The first anomaly detector for the platform.
 *
 * <p>It deliberately uses understandable delay thresholds so its decisions
 * can be explained to users. A trained model can replace these rules once
 * enough Route 64 history has been collected.</p>
 */
@Service
@RequiredArgsConstructor
public class RouteAnomalyDetectionServiceImpl
        implements RouteAnomalyDetectionService {

    private static final long MINIMUM_OBSERVATIONS = 10;
    private static final BigDecimal MEDIUM_AVERAGE_DELAY =
            BigDecimal.valueOf(300);
    private static final BigDecimal HIGH_AVERAGE_DELAY =
            BigDecimal.valueOf(900);
    private static final BigDecimal HIGH_P90_DELAY =
            BigDecimal.valueOf(1800);

    private final RouteReliabilityService routeReliabilityService;

    /**
     * Classifies delay behaviour within a one-hour to seven-day window.
     */
    @Override
    @Transactional(readOnly = true)
    public RouteAnomalyResponse checkRoute(
            Long routeId,
            int hours
    ) {
        int safeHours = Math.min(Math.max(hours, 1), 168);

        RouteReliabilityResponse reliability =
                routeReliabilityService.getRouteReliability(
                        routeId,
                        safeHours
                );

        Long observationCount = reliability.observationCount();

        if (observationCount == null
                || observationCount < MINIMUM_OBSERVATIONS) {
            return response(
                    reliability,
                    safeHours,
                    false,
                    "INSUFFICIENT_DATA",
                    "At least "
                            + MINIMUM_OBSERVATIONS
                            + " observations are required for anomaly detection."
            );
        }

        boolean severeAverageDelay = isAtLeast(
                reliability.averageDelaySeconds(),
                HIGH_AVERAGE_DELAY
        );

        boolean severeP90Delay = isAtLeast(
                reliability.p90DelaySeconds(),
                HIGH_P90_DELAY
        );

        if (severeAverageDelay || severeP90Delay) {
            return response(
                    reliability,
                    safeHours,
                    true,
                    "HIGH",
                    "Severe disruption detected: average delay exceeds "
                            + "15 minutes or P90 delay exceeds 30 minutes."
            );
        }

        if (isAtLeast(
                reliability.averageDelaySeconds(),
                MEDIUM_AVERAGE_DELAY
        )) {
            return response(
                    reliability,
                    safeHours,
                    true,
                    "MEDIUM",
                    "Elevated delay detected: average delay exceeds 5 minutes."
            );
        }

        return response(
                reliability,
                safeHours,
                false,
                "NONE",
                "No unusual delay pattern was detected."
        );
    }

    private RouteAnomalyResponse response(
            RouteReliabilityResponse reliability,
            int hours,
            boolean anomalyDetected,
            String severity,
            String reason
    ) {
        return new RouteAnomalyResponse(
                reliability.routeId(),
                reliability.routeShortName(),
                hours,
                reliability.observationCount(),
                reliability.averageDelaySeconds(),
                reliability.p90DelaySeconds(),
                anomalyDetected,
                severity,
                reason
        );
    }

    private boolean isAtLeast(
            BigDecimal value,
            BigDecimal threshold
    ) {
        return value != null && value.compareTo(threshold) >= 0;
    }
}
