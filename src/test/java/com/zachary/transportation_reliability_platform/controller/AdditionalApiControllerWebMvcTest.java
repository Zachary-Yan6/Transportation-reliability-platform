package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.LiveVehiclePositionResponse;
import com.zachary.transportation_reliability_platform.dto.TripReliabilityResponse;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.LiveTripStateService;
import com.zachary.transportation_reliability_platform.service.LiveVehiclePositionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import com.zachary.transportation_reliability_platform.service.StopService;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import com.zachary.transportation_reliability_platform.service.TripReliabilityService;
import com.zachary.transportation_reliability_platform.service.TripService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayObservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice coverage for the read-only endpoints not exercised by the main
 * controller test. All collaborators are mocks; no database is required.
 */
@WebMvcTest({
        StopController.class,
        TripScheduleController.class,
        TripReliabilityController.class,
        TripDelayController.class,
        TripLiveStateController.class,
        LiveVehiclePositionController.class,
        ServiceAlertController.class
})
class AdditionalApiControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StopService stopService;
    @MockBean
    private TripStopDelayObservationService delayObservationService;
    @MockBean
    private TripService tripService;
    @MockBean
    private StopTimeService stopTimeService;
    @MockBean
    private TripReliabilityService tripReliabilityService;
    @MockBean
    private LiveTripStateService liveTripStateService;
    @MockBean
    private LiveVehiclePositionService liveVehiclePositionService;
    @MockBean
    private RouteService routeService;
    @MockBean
    private ServiceAlertService serviceAlertService;

    @Test
    void stopEndpointsReturnStaticDataAndUseTheDefaultDelayLimit() throws Exception {
        Stop stop = new Stop();
        stop.setId(1L);
        stop.setExternalStopId("STOP-1");
        stop.setStopName("Central Stop");
        when(stopService.getRequiredById(1L)).thenReturn(stop);
        when(delayObservationService.findRecentByStopId(1L, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stops/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopName").value("Central Stop"));
        mockMvc.perform(get("/api/v1/stops/1/delays"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(delayObservationService).findRecentByStopId(1L, 20);
    }

    @Test
    void tripScheduleAndHistoricalDelaysCheckTheTripBeforeDelegating() throws Exception {
        when(tripService.getRequiredById(2L)).thenReturn(trip());
        when(stopTimeService.getScheduleByTripId(2L)).thenReturn(List.of());
        when(delayObservationService.findRecentByTripId(2L, 3)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trips/2/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/trips/2/delays").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(stopTimeService).getScheduleByTripId(2L);
        verify(delayObservationService).findRecentByTripId(2L, 3);
    }

    @Test
    void tripReliabilityAndLiveStateEndpointsReturnTheirServiceResponses() throws Exception {
        when(tripReliabilityService.getTripReliability(2L)).thenReturn(
                new TripReliabilityResponse(
                        2L, "TRIP-2", 20L, BigDecimal.valueOf(120), 300,
                        BigDecimal.valueOf(240), BigDecimal.valueOf(70),
                        BigDecimal.valueOf(70), "HIGH"
                )
        );
        when(tripService.getRequiredById(2L)).thenReturn(trip());
        when(liveTripStateService.findByTrip(1L, "TRIP-2")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trips/2/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence").value("HIGH"));
        mockMvc.perform(get("/api/v1/trips/2/live-delays"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(liveTripStateService).findByTrip(1L, "TRIP-2");
    }

    @Test
    void liveVehicleEndpointReturnsAllVehiclesOrFiltersByInternalRouteId() throws Exception {
        LiveVehiclePositionResponse vehicleA = vehicle("VEHICLE-A", "A");
        LiveVehiclePositionResponse vehicleB = vehicle("VEHICLE-B", "B");
        when(liveVehiclePositionService.findAll()).thenReturn(List.of(vehicleA, vehicleB));
        Route route = new Route();
        route.setId(7L);
        route.setExternalRouteId("B");
        when(routeService.getRequiredById(7L)).thenReturn(route);

        mockMvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/vehicles/live").param("routeId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalRouteId").value("B"));

        verify(routeService).getRequiredById(7L);
    }

    @Test
    void alertEndpointUsesItsDefaultLimit() throws Exception {
        when(serviceAlertService.findRecentAlerts(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/alerts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(serviceAlertService).findRecentAlerts(20);
    }

    private static Trip trip() {
        Trip trip = new Trip();
        trip.setId(2L);
        trip.setFeedVersionId(1L);
        trip.setExternalTripId("TRIP-2");
        return trip;
    }

    private static LiveVehiclePositionResponse vehicle(String vehicleId, String routeId) {
        return new LiveVehiclePositionResponse(
                UUID.randomUUID(), vehicleId, "TRIP-2", routeId,
                "12:00:00", "20260911", (short) 0, 53.3, -6.2, 90.0,
                Instant.parse("2026-09-11T12:00:00Z"),
                OffsetDateTime.now(ZoneOffset.UTC), "NTA_VEHICLES_REALTIME"
        );
    }
}
