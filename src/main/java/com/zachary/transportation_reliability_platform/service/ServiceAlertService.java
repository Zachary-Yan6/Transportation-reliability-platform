package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.RouteAlertEvaluationResponse;
import com.zachary.transportation_reliability_platform.dto.ServiceAlertResponse;
import com.zachary.transportation_reliability_platform.entity.ServiceAlert;

import java.util.List;

/**
 * Creates, refreshes, resolves, and reads operational service alerts.
 */
public interface ServiceAlertService extends IService<ServiceAlert> {

    RouteAlertEvaluationResponse evaluateRouteAnomaly(
            Long routeId,
            int hours
    );

    List<ServiceAlertResponse> findRecentAlerts(int limit);
}
