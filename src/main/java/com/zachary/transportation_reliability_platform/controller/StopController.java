package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.StopDelayObservationResponse;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.service.StopService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stops")
@RequiredArgsConstructor
public class StopController {

    private final StopService stopService;
    private final TripStopDelayObservationService delayObservationService;

    /**
     * Returns the static GTFS information for one stop.
     */
    @GetMapping("/{stopId}")
    public Stop getStopById(@PathVariable Long stopId) {
        return stopService.getRequiredById(stopId);
    }

    /**
     * Returns recent live delay events recorded at this stop.
     */
    @GetMapping("/{stopId}/delays")
    public List<StopDelayObservationResponse> getRecentDelays(
            @PathVariable Long stopId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        // Return 404 instead of an empty response for an invalid stop ID.
        stopService.getRequiredById(stopId);

        return delayObservationService.findRecentByStopId(stopId, limit);
    }
}