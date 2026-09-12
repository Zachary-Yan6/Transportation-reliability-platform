package com.zachary.transportation_reliability_platform.controller;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.zachary.transportation_reliability_platform.common.exception.GlobalExceptionHandler;
import com.zachary.transportation_reliability_platform.dto.DashboardSummaryResponse;
import com.zachary.transportation_reliability_platform.dto.PublishTripUpdateRequest;
import com.zachary.transportation_reliability_platform.dto.RouteReliabilityResponse;
import com.zachary.transportation_reliability_platform.dto.TripOperationStatusResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.service.DashboardService;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RouteAnomalyDetectionService;
import com.zachary.transportation_reliability_platform.service.RouteReliabilityService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import com.zachary.transportation_reliability_platform.service.TripOperationService;
import com.zachary.transportation_reliability_platform.service.TripService;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.zachary.transportation_reliability_platform.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice tests: Spring creates the controller, JSON conversion, validation,
 * and controller advice, while every external service is a Mockito mock.
 */
@WebMvcTest(controllers = {
        DashboardController.class,
        RouteController.class,
        TripUpdateEventController.class,
        TripOperationController.class
}, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = JwtAuthenticationFilter.class
))
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = ApiControllerWebMvcTest.MvcTestConfiguration.class)
class ApiControllerWebMvcTest {

    /**
     * Keeps this a web-layer test. In particular, it prevents the application's
     * @MapperScan from starting MyBatis mappers that need a real SqlSessionFactory.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(GlobalExceptionHandler.class)
    static class MvcTestConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;
    @MockBean
    private FeedVersionService feedVersionService;
    @MockBean
    private RouteService routeService;
    @MockBean
    private RouteReliabilityService routeReliabilityService;
    @MockBean
    private RouteAnomalyDetectionService routeAnomalyDetectionService;
    @MockBean
    private ServiceAlertService serviceAlertService;
    @MockBean
    private TripService tripService;
    @MockBean
    private TripUpdateEventProducer tripUpdateEventProducer;
    @MockBean
    private TripOperationService tripOperationService;

    @Test
    void dashboardUsesExplicitFeedVersionWithoutLookingUpTheActiveFeed() throws Exception {
        when(dashboardService.getSummary(7L, 1)).thenReturn(summary(7L, 1));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("feedVersionId", "7")
                        .param("hours", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedVersionId").value(7))
                .andExpect(jsonPath("$.hours").value(1))
                .andExpect(jsonPath("$.confidence").value("HIGH"));

        verify(dashboardService).getSummary(7L, 1);
        verify(feedVersionService, never()).findActive();
    }

    @Test
    void dashboardResolvesTheActiveFeedAndReturnsAJsonErrorWhenNoneExists() throws Exception {
        FeedVersion active = new FeedVersion();
        active.setId(4L);
        when(feedVersionService.findActive()).thenReturn(Optional.of(active));
        when(dashboardService.getSummary(4L, 24)).thenReturn(summary(4L, 24));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedVersionId").value(4));

        when(feedVersionService.findActive()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/v1/dashboard/summary"));
    }

    @Test
    void routesSupportFeedLookupAndBothReliabilityOverloads() throws Exception {
        FeedVersion active = new FeedVersion();
        active.setId(2L);
        Route route = new Route();
        route.setId(9L);
        route.setFeedVersionId(2L);
        route.setExternalRouteId("R9");
        route.setShortName("9");
        route.setLongName("Test route");

        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<Route> query = org.mockito.Mockito.mock(LambdaQueryChainWrapper.class);
        when(feedVersionService.findActive()).thenReturn(Optional.of(active));
        when(routeService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), eq(2L))).thenReturn(query);
        when(query.list()).thenReturn(List.of(route));
        when(routeReliabilityService.getRouteReliability(9L)).thenReturn(routeReliability(9L));
        when(routeReliabilityService.getRouteReliability(9L, 6)).thenReturn(routeReliability(9L));

        mockMvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].shortName").value("9"));
        mockMvc.perform(get("/api/v1/routes/9/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(9));
        mockMvc.perform(get("/api/v1/routes/9/reliability").param("hours", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageDelaySeconds").value(60));

        verify(routeReliabilityService).getRouteReliability(9L);
        verify(routeReliabilityService).getRouteReliability(9L, 6);
    }

    @Test
    void validTripUpdateIsAcceptedAndInvalidBodyGetsAValidationResponse() throws Exception {
        TripUpdateEvent event = new TripUpdateEvent(
                UUID.fromString("31dd44c5-56e3-4ac4-a9bd-549d4b16a64b"),
                1L,
                "TRIP-1",
                "STOP-1",
                2,
                60,
                Instant.parse("2026-09-10T12:00:00Z")
        );
        when(tripUpdateEventProducer.publish(
                any(PublishTripUpdateRequest.class)
        )).thenReturn(event);

        mockMvc.perform(post("/api/v1/events/trip-updates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedVersionId":1,"externalTripId":"TRIP-1","externalStopId":"STOP-1","stopSequence":2,"delaySeconds":60}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(event.eventId().toString()));

        mockMvc.perform(post("/api/v1/events/trip-updates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void tripOperationParsesIsoDatesAndDelegatesToTheOperationService() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 10);
        when(tripOperationService.getOperationStatus(3L, date))
                .thenReturn(new TripOperationStatusResponse(3L, "TRIP-3", date, true, "Calendar service"));

        mockMvc.perform(get("/api/v1/trips/3/operation-status").param("date", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(3))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.serviceDate").value("2026-09-10"));

        verify(tripOperationService).getOperationStatus(3L, date);
    }

    private static DashboardSummaryResponse summary(long feedVersionId, int hours) {
        return new DashboardSummaryResponse(
                feedVersionId,
                hours,
                20L,
                4L,
                2L,
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(80),
                OffsetDateTime.parse("2026-09-10T12:00:00Z"),
                "HIGH"
        );
    }

    private static RouteReliabilityResponse routeReliability(long routeId) {
        return new RouteReliabilityResponse(
                routeId,
                "R" + routeId,
                String.valueOf(routeId),
                "Route " + routeId,
                20L,
                BigDecimal.valueOf(60),
                300,
                BigDecimal.valueOf(120),
                BigDecimal.valueOf(80),
                BigDecimal.valueOf(80),
                "HIGH"
        );
    }
}
