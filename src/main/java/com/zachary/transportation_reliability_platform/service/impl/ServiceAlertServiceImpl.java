package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.dto.RouteAlertEvaluationResponse;
import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;
import com.zachary.transportation_reliability_platform.dto.ServiceAlertResponse;
import com.zachary.transportation_reliability_platform.entity.ServiceAlert;
import com.zachary.transportation_reliability_platform.mapper.ServiceAlertMapper;
import com.zachary.transportation_reliability_platform.service.RouteAnomalyDetectionService;
import com.zachary.transportation_reliability_platform.service.ServiceAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stores one active delay-anomaly alert per route and resolves it when the
 * detector reports that the route has recovered.
 */
@Service
@RequiredArgsConstructor
public class ServiceAlertServiceImpl
        extends ServiceImpl<ServiceAlertMapper, ServiceAlert>
        implements ServiceAlertService {

    private static final String DELAY_ANOMALY = "DELAY_ANOMALY";
    private static final String ACTIVE = "ACTIVE";
    private static final String RESOLVED = "RESOLVED";

    private final RouteAnomalyDetectionService routeAnomalyDetectionService;

    /**
     * Creates or refreshes an active alert, or resolves the active alert when
     * the route no longer satisfies the anomaly rules.
     */
    @Override
    @Transactional
    public RouteAlertEvaluationResponse evaluateRouteAnomaly(
            Long routeId,
            int hours
    ) {
        RouteAnomalyResponse anomaly =
                routeAnomalyDetectionService.checkRoute(routeId, hours);

        ServiceAlert activeAlert = lambdaQuery()
                .eq(ServiceAlert::getRouteId, routeId)
                .eq(ServiceAlert::getAlertType, DELAY_ANOMALY)
                .eq(ServiceAlert::getStatus, ACTIVE)
                .one();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (anomaly.anomalyDetected()) {
            ServiceAlert alert = activeAlert == null
                    ? createActiveAlert(anomaly, now)
                    : refreshActiveAlert(activeAlert, anomaly, now);

            return new RouteAlertEvaluationResponse(
                    anomaly,
                    toResponse(alert)
            );
        }

        // A missing or tiny sample is not proof that service has recovered.
        // Keep the current alert active until a sufficiently large normal
        // sample produces severity NONE.
        if ("INSUFFICIENT_DATA".equals(anomaly.severity())) {
            return new RouteAlertEvaluationResponse(
                    anomaly,
                    activeAlert == null ? null : toResponse(activeAlert)
            );
        }

        if (activeAlert != null) {
            activeAlert.setStatus(RESOLVED);
            activeAlert.setResolvedAt(now);
            updateById(activeAlert);

            return new RouteAlertEvaluationResponse(
                    anomaly,
                    toResponse(activeAlert)
            );
        }

        return new RouteAlertEvaluationResponse(anomaly, null);
    }

    /**
     * Returns alerts sorted by their most recent detection time.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ServiceAlertResponse> findRecentAlerts(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        return lambdaQuery()
                .orderByDesc(ServiceAlert::getLastDetectedAt)
                .last("LIMIT " + safeLimit)
                .list()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ServiceAlert createActiveAlert(
            RouteAnomalyResponse anomaly,
            OffsetDateTime now
    ) {
        ServiceAlert alert = new ServiceAlert();

        alert.setRouteId(anomaly.routeId());
        alert.setAlertType(DELAY_ANOMALY);
        alert.setSeverity(anomaly.severity());
        alert.setStatus(ACTIVE);
        alert.setMessage(anomaly.reason());
        alert.setFirstDetectedAt(now);
        alert.setLastDetectedAt(now);
        alert.setResolvedAt(null);

        save(alert);
        return alert;
    }

    private ServiceAlert refreshActiveAlert(
            ServiceAlert alert,
            RouteAnomalyResponse anomaly,
            OffsetDateTime now
    ) {
        alert.setSeverity(anomaly.severity());
        alert.setMessage(anomaly.reason());
        alert.setLastDetectedAt(now);
        alert.setResolvedAt(null);

        updateById(alert);
        return alert;
    }

    private ServiceAlertResponse toResponse(ServiceAlert alert) {
        return new ServiceAlertResponse(
                alert.getId(),
                alert.getRouteId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getMessage(),
                alert.getFirstDetectedAt(),
                alert.getLastDetectedAt(),
                alert.getResolvedAt()
        );
    }
}
