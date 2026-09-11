package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.TripDelayObservationResponse;
import com.zachary.transportation_reliability_platform.service.TripService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripDelayController {

    private final TripService tripService;
    private final TripStopDelayObservationService delayObservationService;

    @GetMapping("/{tripId}/delays")
    public List<TripDelayObservationResponse> getRecentDelays(
            @PathVariable Long tripId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        // Return 404 when the internal trip ID does not exist.
        tripService.getRequiredById(tripId);

        // Return the newest observations first.
        return delayObservationService.findRecentByTripId(
                tripId,
                limit
        );
    }
}