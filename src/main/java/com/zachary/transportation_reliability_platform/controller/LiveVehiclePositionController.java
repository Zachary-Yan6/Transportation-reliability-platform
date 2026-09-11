package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.LiveVehiclePositionResponse;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.service.LiveVehiclePositionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the latest fresh GPS positions for the live vehicle map.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class LiveVehiclePositionController {

    private final LiveVehiclePositionService liveVehiclePositionService;
    private final RouteService routeService;

    /**
     * Optionally filters the network by the application's internal route ID.
     */
    @GetMapping("/live")
    public List<LiveVehiclePositionResponse> getLiveVehicles(
            @RequestParam(required = false) Long routeId
    ) {
        List<LiveVehiclePositionResponse> vehicles =
                liveVehiclePositionService.findAll();

        if (routeId == null) {
            return vehicles;
        }

        Route route = routeService.getRequiredById(routeId);
        return vehicles.stream()
                .filter(vehicle -> route.getExternalRouteId().equals(
                        vehicle.externalRouteId()))
                .toList();
    }
}
