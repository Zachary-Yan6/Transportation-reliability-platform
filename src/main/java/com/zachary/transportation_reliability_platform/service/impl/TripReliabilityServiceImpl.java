package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.TripReliabilityResponse;
import com.zachary.transportation_reliability_platform.dto.TripReliabilityStatisticsRow;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.TripReliabilityService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TripReliabilityServiceImpl
        implements TripReliabilityService {

    private final TripService tripService;
    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public TripReliabilityResponse getTripReliability(Long tripId) {

        // Confirm that the requested Trip exists.
        Trip trip = tripService.getRequiredById(tripId);

        // Let PostgreSQL calculate the aggregate values.
        TripReliabilityStatisticsRow statistics =
                delayObservationMapper.calculateReliability(tripId);

        // A score based on very little data should not be considered reliable.
        String confidence = calculateConfidence(
                statistics.getObservationCount()
        );

        // For the MVP, reliability score equals the on-time percentage.
        return new TripReliabilityResponse(
                trip.getId(),
                trip.getExternalTripId(),
                statistics.getObservationCount(),
                statistics.getAverageDelaySeconds(),
                statistics.getMaximumDelaySeconds(),
                statistics.getP90DelaySeconds(),
                statistics.getOnTimePercentage(),
                statistics.getOnTimePercentage(),
                confidence
        );
    }


    /**
     * Indicates how trustworthy the score is based on sample size.
     */
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