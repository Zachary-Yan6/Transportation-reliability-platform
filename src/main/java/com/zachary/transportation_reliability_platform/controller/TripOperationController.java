package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.TripOperationStatusResponse;
import com.zachary.transportation_reliability_platform.service.TripOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripOperationController {

    private final TripOperationService tripOperationService;

    @GetMapping("/{tripId}/operation-status")
    public TripOperationStatusResponse getOperationStatus(
            @PathVariable Long tripId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        // query if a trip would operate in a specific day
        return tripOperationService.getOperationStatus(tripId, date);
    }
}