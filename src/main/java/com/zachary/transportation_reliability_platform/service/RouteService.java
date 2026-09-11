package com.zachary.transportation_reliability_platform.service;


import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.entity.Route;

import java.util.Optional;

public interface RouteService extends IService<Route> {
    Route getRequiredById(Long id);

    Optional<Route> findByFeedVersionAndExternalRouteId(
            Long feedVersionId,
            String externalRouteId
    );
}
