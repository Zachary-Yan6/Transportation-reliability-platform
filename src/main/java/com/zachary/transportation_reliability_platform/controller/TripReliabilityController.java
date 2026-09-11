package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.TripReliabilityResponse;
import com.zachary.transportation_reliability_platform.service.TripReliabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripReliabilityController {

    private final TripReliabilityService tripReliabilityService;

    /**
     * Returns reliability statistics for one internal Trip ID.
     */
    @GetMapping("/{tripId}/reliability")
    public TripReliabilityResponse getTripReliability(
            @PathVariable Long tripId
    ) {
        // reliability includes avg delay, maximum delay, on-time percentage.
        // we take on-time percentage as final reliability
        return tripReliabilityService.getTripReliability(tripId);
    }
}