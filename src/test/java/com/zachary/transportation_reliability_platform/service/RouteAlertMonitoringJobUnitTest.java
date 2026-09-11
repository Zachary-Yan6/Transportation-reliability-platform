package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.zachary.transportation_reliability_platform.dto.RouteAlertEvaluationResponse;
import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.service.scheduler.RouteAlertMonitoringJob;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests route-specific and full-network alert monitoring without scheduling delays. */
class RouteAlertMonitoringJobUnitTest {

    @Test
    void selectedRouteUsesTheActiveFeedAndSkipsGracefullyWhenNoRouteExists() {
        RouteService routeService = mock(RouteService.class);
        ServiceAlertService alertService = mock(ServiceAlertService.class);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        FeedVersion active = new FeedVersion();
        active.setId(9L);
        Route route = route(3L, "R3");
        when(feedVersionService.findActive()).thenReturn(Optional.of(active));
        when(routeService.findByFeedVersionAndExternalRouteId(9L, "R3"))
                .thenReturn(Optional.of(route));
        when(alertService.evaluateRouteAnomaly(3L, 24)).thenReturn(evaluation());
        RouteAlertMonitoringJob job = job(routeService, alertService, feedVersionService,
                1L, "R3", 24);

        job.evaluateTargetRoute();
        verify(alertService).evaluateRouteAnomaly(3L, 24);

        when(routeService.findByFeedVersionAndExternalRouteId(9L, "R3"))
                .thenReturn(Optional.empty());
        job.evaluateTargetRoute();
        verify(alertService).evaluateRouteAnomaly(3L, 24);
    }

    @Test
    void blankTargetEvaluatesEveryImportedRouteAndContainsOneRouteFailure() {
        RouteService routeService = mock(RouteService.class);
        ServiceAlertService alertService = mock(ServiceAlertService.class);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<Route> query = mock(LambdaQueryChainWrapper.class);
        when(feedVersionService.findActive()).thenReturn(Optional.empty());
        when(routeService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), eq(1L))).thenReturn(query);
        when(query.list()).thenReturn(List.of(route(3L, "R3"), route(4L, "R4")));
        when(alertService.evaluateRouteAnomaly(3L, 24)).thenReturn(evaluation());
        when(alertService.evaluateRouteAnomaly(4L, 24))
                .thenThrow(new IllegalStateException("temporary database failure"));
        RouteAlertMonitoringJob job = job(routeService, alertService, feedVersionService,
                1L, " ", 24);

        job.evaluateTargetRoute();

        verify(alertService).evaluateRouteAnomaly(3L, 24);
        verify(alertService).evaluateRouteAnomaly(4L, 24);
    }

    private static RouteAlertMonitoringJob job(
            RouteService routeService,
            ServiceAlertService alertService,
            FeedVersionService feedVersionService,
            Long configuredFeedVersionId,
            String targetExternalRouteId,
            int hours
    ) {
        RouteAlertMonitoringJob job = new RouteAlertMonitoringJob(
                routeService, alertService, feedVersionService
        );
        ReflectionTestUtils.setField(job, "feedVersionId", configuredFeedVersionId);
        ReflectionTestUtils.setField(job, "targetExternalRouteId", targetExternalRouteId);
        ReflectionTestUtils.setField(job, "evaluationWindowHours", hours);
        return job;
    }

    private static Route route(Long id, String externalRouteId) {
        Route route = new Route();
        route.setId(id);
        route.setExternalRouteId(externalRouteId);
        return route;
    }

    private static RouteAlertEvaluationResponse evaluation() {
        return new RouteAlertEvaluationResponse(
                new RouteAnomalyResponse(3L, "3", 24, 20L, null, null,
                        false, "NONE", "Normal"),
                null
        );
    }
}
