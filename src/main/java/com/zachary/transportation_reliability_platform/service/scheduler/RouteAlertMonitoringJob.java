package com.zachary.transportation_reliability_platform.service.scheduler;

import com.zachary.transportation_reliability_platform.dto.RouteAlertEvaluationResponse;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates the selected route, or every imported route when the polling scope
 * is all routes, after Kafka has had time to save recent delay observations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.nta.alert-monitoring",
        name = "enabled",
        havingValue = "true"
)
public class RouteAlertMonitoringJob {

    private final RouteService routeService;
    private final ServiceAlertService serviceAlertService;
    private final FeedVersionService feedVersionService;

    @Value("${app.nta.realtime-polling.feed-version-id}")
    private Long feedVersionId;

    @Value("${app.nta.realtime-polling.target-external-route-id:}")
    private String targetExternalRouteId;

    @Value("${app.nta.alert-monitoring.evaluation-window-hours}")
    private int evaluationWindowHours;

    /**
     * Runs one minute after startup and then every five minutes. This offset
     * lets the Kafka consumer persist updates before they are evaluated.
     */
    @Scheduled(
            initialDelayString = "${app.nta.alert-monitoring.initial-delay-ms}",
            fixedDelayString = "${app.nta.alert-monitoring.fixed-delay-ms}"
    )
    public void evaluateTargetRoute() {
        Long activeFeedVersionId = activeFeedVersionId();
        if (targetExternalRouteId == null
                || targetExternalRouteId.isBlank()) {
            evaluateAllImportedRoutes(activeFeedVersionId);
            return;
        }

        try {
            routeService.findByFeedVersionAndExternalRouteId(
                            activeFeedVersionId,
                            targetExternalRouteId
                    )
                    .ifPresentOrElse(
                            route -> evaluateRoute(route.getId()),
                            () -> log.warn(
                                    "Alert monitoring skipped: route {} was not found "
                                            + "in feed version {}",
                                    targetExternalRouteId,
                                    activeFeedVersionId
                            )
                    );
        } catch (Exception exception) {
            // Do not stop future scheduled checks because of one failure.
            log.error(
                    "Automatic alert evaluation failed for route {}",
                    targetExternalRouteId,
                    exception
            );
        }
    }

    /**
     * Full-network collection should also receive full-network anomaly checks.
     * Routes without enough observations simply return INSUFFICIENT_DATA and do
     * not create a misleading alert.
     */
    private void evaluateAllImportedRoutes(Long activeFeedVersionId) {
        List<Route> routes = routeService.lambdaQuery()
                .eq(Route::getFeedVersionId, activeFeedVersionId)
                .list();

        for (Route route : routes) {
            try {
                evaluateRoute(route.getId());
            } catch (Exception exception) {
                // One route must not stop monitoring the rest of the network.
                log.error(
                        "Automatic alert evaluation failed for route {}",
                        route.getExternalRouteId(),
                        exception
                );
            }
        }

        log.info("Automatic alert evaluation completed for {} routes", routes.size());
    }

    private Long activeFeedVersionId() {
        return feedVersionService.findActive()
                .map(feedVersion -> feedVersion.getId())
                .orElse(feedVersionId);
    }

    private void evaluateRoute(Long routeId) {
        RouteAlertEvaluationResponse result =
                serviceAlertService.evaluateRouteAnomaly(
                        routeId,
                        evaluationWindowHours
                );

        String alertStatus = result.alert() == null
                ? "NO_ALERT"
                : result.alert().status();

        log.debug(
                "Route alert monitoring completed: route={}, anomaly={}, "
                        + "severity={}, alertStatus={}",
                result.anomaly().routeShortName(),
                result.anomaly().anomalyDetected(),
                result.anomaly().severity(),
                alertStatus
        );
    }
}
