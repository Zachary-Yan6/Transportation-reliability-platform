package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.StopTimeScheduleResponse;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripScheduleController {

    private final TripService tripService;
    private final StopTimeService stopTimeService;

    @GetMapping("/{tripId}/schedule")
    public List<StopTimeScheduleResponse> getSchedule(
            @PathVariable Long tripId
    ) {
        // check if this trip is existing
        tripService.getRequiredById(tripId);

        // search all stop information(sequence, id, name, arrival, departure) in a trip
        return stopTimeService.getScheduleByTripId(tripId);
    }
}