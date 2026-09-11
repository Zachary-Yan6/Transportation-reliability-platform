package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.LiveTripStateService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the latest known NTA stop-delay state for a single trip.
 */
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripLiveStateController {

    private final TripService tripService;
    private final LiveTripStateService liveTripStateService;

    @GetMapping("/{tripId}/live-delays")
    public List<LiveTripStopDelayResponse> getLiveDelays(
            @PathVariable Long tripId
    ) {
        Trip trip = tripService.getRequiredById(tripId);

        return liveTripStateService.findByTrip(
                trip.getFeedVersionId(),
                trip.getExternalTripId()
        );
    }
}
