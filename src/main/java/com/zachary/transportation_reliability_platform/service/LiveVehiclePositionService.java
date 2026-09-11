package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.LiveVehiclePositionResponse;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;

import java.util.List;

/**
 * Maintains the short-lived latest known location for active vehicles.
 */
public interface LiveVehiclePositionService {

    void update(VehiclePositionEvent event);

    List<LiveVehiclePositionResponse> findAll();
}
